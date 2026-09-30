package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.ConnectingWords
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.RuntimeState.*
import io.github.kzagoris.proxenos.coreapi.RuntimeStatus
import io.github.kzagoris.proxenos.coreapi.TunnelComplaint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.unixSocket
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit.SECONDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The Runtime's link to the tunnel: the tunnel child it supervises, and the
 * judgement of whether that child's link works (ADR 0005).
 *
 * **One number backs every state**: `commands_poll_last_successful_timestamp_seconds`, read from
 * the child's `/metrics` over the Unix socket it was told to bind, every
 * [RuntimeConfig.tunnelReadInterval]. With the instant this Start (or the user's last Connect)
 * happened, it is enough:
 *
 * - **Connecting** — no success since then: the gauge is absent, zero, or older.
 * - **Connected** — the last success is newer than one poll cycle plus a margin.
 * - **Failed** — there was a success, and it has gone stale.
 * - **Disconnected** — the user's intent, and never measured.
 *
 * Connecting is one-way: once a success is seen, the state is Connected or Failed until the user
 * Disconnects. A restarted child's gauge reading zero again does not undo a success already seen.
 *
 * The poll cycle is the child's own configuration, not a guess: it is a long poll, so a healthy
 * idle tunnel's gauge is routinely one wait plus one guardrail old — ~35s by default — and the
 * threshold moves with the wait the Runtime launched the child with.
 *
 * **Failed is a judgement, not an action.** The child never gives up and never exits on a
 * rejected key, and nothing here restarts it for a stale link: that would be stopping a poller
 * that is still trying. When a poll succeeds again the state is Connected with no keypress.
 *
 * **Logs supply the words and never the state.** The child's most recent control-plane warning
 * since the last success is quoted when the link is judged Failed. `/healthz` and `/readyz` are
 * never asked: the first checks nothing, and the second answered ready while every poll failed.
 *
 * **A Connecting that lasts gets words too**: once one long-poll wait has passed with no
 * success, [connectingWords] carries the tunnel's complaint — a rejected key loops on a 401
 * forever, and Connecting is still all that is true about it. At that moment
 * `/health/control-plane` is asked once, a feature probe: where the client has the route its
 * failure category joins the words, and where it 404s, as v0.0.14 does, the words are the quote.
 *
 * **What Connected does not say**: a connector deleted in the ChatGPT UI leaves the link up and
 * the Runtime Connected, and nothing on this machine can see it.
 */
class Tunnel(
  private val config: RuntimeConfig,
  /** The logical URL the child forwards MCP to, `http://<logical host>/mcp`. */
  mcpUrl: String,
  credentials: TunnelCredentials,
  private val log: (String) -> Unit,
) {
  /** One long-poll wait: long enough for a poll to have answered, one way or the other. */
  private val longPollWait: Duration = config.tunnelPollTimeout + config.tunnelPollGuardrail
  private val threshold: Duration = longPollWait + config.tunnelStalenessMargin
  private val lock = Any()
  private var wanted = !Files.exists(config.disconnectedFile)
  /** Unix seconds from which a success counts: this Start, or the user's last Connect. */
  private var since = nowSeconds()
  private var lastSuccess: Double? = null
  private var complaint: TunnelComplaint? = null
  /** Unix seconds when [complaint] was heard, so a success after it — not merely read after it — retires it. */
  private var complaintHeardAt = 0.0
  private var userConnected = false
  /** Whether `/health/control-plane` has been asked since this Start or Connect, and what it said. */
  private var controlPlaneAsked = false
  private var failureCategory: String? = null
  private var current = RuntimeStatus(if (wanted) Connecting else Disconnected, Instant.now())
  // Every transition is kept for every collector until it is collected: a slow frontend sees
  // each one late rather than missing one. Transitions are at least a read apart, so the
  // buffer stays small.
  private val transitions = MutableSharedFlow<RuntimeStatus>(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
    .apply { tryEmit(current) }
  private val connectingWords = MutableStateFlow<ConnectingWords?>(null)
  /** Connect and Disconnect one at a time, so the child, the file and the state agree. */
  private val acts = Mutex()

  private val child = TunnelChild(
    config.tunnelExecutable, arguments(config, mcpUrl), credentials, config.killGrace, config.tunnelHealthUrlFile,
    log, ::heard,
  )
  private val http = HttpClient(CIO)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * The current state first, then **one value per transition and nothing else**. A failed poll
   * the child retries is not a transition, so the stream is silent through the whole retry
   * window; a frontend wanting a spinner has [RuntimeStatus.enteredAt] and a clock of its own.
   */
  fun observe(): SharedFlow<RuntimeStatus> = transitions.asSharedFlow()

  /** The state now, and when it was entered. */
  fun status(): RuntimeStatus = synchronized(lock) { current }

  /**
   * The words beside a Connecting that has lasted one long-poll wait, and `null` in every other
   * case. Never a state input: nothing in [judge] reads it.
   */
  fun connectingWords(): StateFlow<ConnectingWords?> = connectingWords.asStateFlow()

  fun start() {
    say(status().state)
    child.start(connected = synchronized(lock) { wanted })
    scope.launch {
      while (isActive) {
        val reading = if (synchronized(lock) { wanted }) readGauge() else null
        if (synchronized(lock) { !controlPlaneAsked && current.state == Connecting && oneLongPollWaitPassed() }) probeControlPlane()
        judge(reading)
        delay(config.tunnelReadInterval)
      }
    }
  }

  /** Blocks until [stop] has ended supervision. */
  fun join() = child.join()

  /**
   * Takes the transport down: the child's tree is ended and no other is started. Nothing
   * else is touched — this holds no registration, no Access Level and no running Operation.
   * The intent is written first, so a restart comes back Disconnected.
   */
  suspend fun disconnect(): Unit = acts.withLock { disconnecting() }

  private suspend fun disconnecting() = withContext(Dispatchers.IO) {
    Files.writeString(config.disconnectedFile, "")
    synchronized(lock) {
      if (!wanted) return@withContext
      wanted = false
      enter(Disconnected)
      connectingWords.value = null
    }
    child.disconnect()
  }

  /**
   * Brings the transport back. It reads Connecting until a poll succeeds: nothing has been
   * measured since the user asked for the link, and a success from before they took it down
   * says nothing about the new one.
   */
  suspend fun connect(): Unit = acts.withLock { connecting() }

  private suspend fun connecting() = withContext(Dispatchers.IO) {
    Files.deleteIfExists(config.disconnectedFile)
    synchronized(lock) {
      if (wanted) return@withContext
      wanted = true
      since = nowSeconds()
      lastSuccess = null
      complaint = null
      controlPlaneAsked = false
      failureCategory = null
      userConnected = true
      enter(Connecting)
    }
    child.connect()
  }

  /** Ends the child's tree and the reading, for Runtime Stop. */
  fun stop() {
    child.stop()
    scope.cancel()
    http.close()
  }

  private fun judge(reading: Double?): Unit = synchronized(lock) {
    if (!wanted) return
    // Older than Start (or Connect) is the one rule that makes absent, zero and a leftover value
    // all read as no success yet.
    if (reading != null && reading >= since && reading > (lastSuccess ?: 0.0)) {
      lastSuccess = reading
      // Whatever the tunnel said before this success is not why a later one went stale.
      if (complaintHeardAt < reading) complaint = null
    }
    val last = lastSuccess
    val next = when {
      last == null -> Connecting
      nowSeconds() - last <= threshold.toDouble(SECONDS) -> Connected
      else -> Failed(complaint)
    }
    if (next::class != current.state::class) enter(next)
    connectingWords.value = if (next == Connecting && oneLongPollWaitPassed()) ConnectingWords(complaint, failureCategory) else null
  }

  /** One long-poll wait has passed since this Start or Connect: long enough for a first poll to have answered. */
  private fun oneLongPollWaitPassed(): Boolean = nowSeconds() - since >= longPollWait.toDouble(SECONDS)

  /**
   * Asks `/health/control-plane` once, when the words are first due rather than at the instant of
   * Start: a client asked then has failed nothing yet, so its category would say nothing. Any
   * answer but a 200 — v0.0.14's 404 above all — leaves the words as the quote alone, and says
   * nothing: it is a feature probe, not a fault. No answer at all — no listener yet, a reset, a
   * timeout — says nothing about the route, so the question waits for the next read.
   */
  private suspend fun probeControlPlane() {
    val socket = healthSocket() ?: return
    val asked = synchronized(lock) { since }
    val reply = fromHealthListener(socket, "/health/control-plane") ?: return
    synchronized(lock) {
      // A Connect while it was asked begins another Connecting, whose question is its own.
      if (since != asked) return
      controlPlaneAsked = true
      failureCategory = reply.takeIf { it.status == HttpStatusCode.OK }?.body?.let(::failureCategoryIn)
    }
  }

  private fun enter(state: RuntimeState) {
    current = RuntimeStatus(state, Instant.now())
    transitions.tryEmit(current)
    say(state)
  }

  private fun say(state: RuntimeState) = log(
    "tunnel link: " + when (state) {
      Connecting -> "Connecting. No poll has succeeded since ${if (userConnected) "Connect" else "this Start"}."
      Connected -> "Connected."
      is Failed -> "Failed. The last successful poll is older than one poll cycle ($threshold); the " +
        "tunnel child keeps retrying on its own. " +
        (state.complaint?.let { "The tunnel said: ${it.quoted()}" } ?: "The tunnel has said nothing about it.")
      Disconnected -> "Disconnected, as the user asked. Calls fail until Connect."
    },
  )

  private fun heard(line: String) {
    val words = complaintIn(line) ?: return
    synchronized(lock) {
      complaint = words
      complaintHeardAt = nowSeconds()
    }
  }

  /** The gauge, or `null` when there is no reading to be had: no child, no listener, no line. */
  private suspend fun readGauge(): Double? {
    val socket = healthSocket() ?: return null
    // Staleness is judged regardless of why there was no reading.
    return fromHealthListener(socket, "/metrics")?.takeIf { it.status == HttpStatusCode.OK }?.body?.let(::lastSuccessfulPoll)
  }

  private class Reply(val status: HttpStatusCode, val body: String)

  /**
   * What the child's health listener answered at [path], or `null` for no answer: refused,
   * reset, timed out, or cut short by a child dying mid-read.
   */
  private suspend fun fromHealthListener(socket: String, path: String): Reply? = try {
    withTimeoutOrNull(READ_TIMEOUT) {
      // The host is never resolved: the request goes down the socket, and the name is only
      // what the Host header says.
      val response = http.get("http://tunnel-client.invalid$path") { unixSocket(socket) }
      Reply(response.status, response.bodyAsText())
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }

  /**
   * The socket the child says its health listener is on, from the base URL it wrote to
   * `--health.url-file` once the listener was up: `http+unix://` and the path, base64url.
   */
  private fun healthSocket(): String? {
    val url = try {
      Files.readString(config.tunnelHealthUrlFile).trim()
    } catch (_: IOException) {
      return null
    }
    if (!url.startsWith(UNIX_SCHEME)) return null
    return try {
      String(Base64.getUrlDecoder().decode(url.removePrefix(UNIX_SCHEME).substringBefore('/')), UTF_8)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  companion object {
    /** `tunnel-client`'s own defaults (v0.0.14), which the Runtime passes explicitly. */
    val POLL_TIMEOUT: Duration = 30.seconds
    val POLL_GUARDRAIL: Duration = 5.seconds
    val STALENESS_MARGIN: Duration = 15.seconds
    val READ_INTERVAL: Duration = 15.seconds

    private val READ_TIMEOUT = 5.seconds
    private const val UNIX_SCHEME = "http+unix://"
    private const val GAUGE = "commands_poll_last_successful_timestamp_seconds"

    /**
     * `tunnel-client run`'s flags. The poll wait and guardrail are passed rather than left to
     * the child's defaults, so an inherited `CONTROL_PLANE_POLL_TIMEOUT` cannot make the poll
     * cycle something other than the one the threshold assumes.
     */
    internal fun arguments(config: RuntimeConfig, mcpUrl: String): List<String> = listOf(
      "run",
      "--mcp.server-url", "url=$mcpUrl,unix-socket=${config.mcpSocket}",
      "--health.unix-socket", config.tunnelHealthSocket.toString(),
      "--health.url-file", config.tunnelHealthUrlFile.toString(),
      "--control-plane.poll-timeout", goDuration(config.tunnelPollTimeout),
      "--control-plane.poll-deadline-guardrail", goDuration(config.tunnelPollGuardrail),
      // The words Failed quotes are read from these lines; JSON is the format with a grammar.
      "--log.format", "json",
    )

    private fun goDuration(duration: Duration): String =
      if (duration.inWholeMilliseconds % 1_000 == 0L) "${duration.inWholeSeconds}s" else "${duration.inWholeMilliseconds}ms"

    /**
     * The gauge from a `/metrics` body: the line beginning with its name, and the second field
     * of that line. Not a Prometheus parser — one number is wanted, and a name that begins with
     * this one's is a different metric.
     */
    internal fun lastSuccessfulPoll(metrics: String): Double? = metrics.lineSequence()
      .firstOrNull { it.startsWith(GAUGE) && it.getOrNull(GAUGE.length).let { next -> next == '{' || next == ' ' } }
      ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toDoubleOrNull()

    /**
     * A control-plane warning, taken apart into the fields [TunnelComplaint] quotes, or `null` for
     * any other line. Keyed on the level and the component rather than on the message text, so a
     * reworded message upstream still yields its words; if the fields go, only the words go.
     */
    internal fun complaintIn(line: String): TunnelComplaint? {
      val fields = jsonObject(line) ?: return null
      if (fields.text("component") != "controlplane" || fields.text("level") !in setOf("WARN", "ERROR")) return null
      return TunnelComplaint(
        statusCode = (fields["status_code"] as? JsonPrimitive)?.intOrNull,
        errorCode = fields.text("error_code"),
        mitigation = fields.text("mitigation"),
        message = fields.text("error_message") ?: fields.text("error"),
      )
    }

    /** `/health/control-plane`'s `details.failure_category`, verbatim, or `null` for a body without one. */
    internal fun failureCategoryIn(body: String): String? = (jsonObject(body)?.get("details") as? JsonObject)?.text("failure_category")

    private fun jsonObject(text: String): JsonObject? = try {
      Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun TunnelComplaint.quoted(): String = listOfNotNull(
      statusCode?.let { "status $it" }, errorCode, mitigation, message,
    ).joinToString(" | ")

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
  }
}
