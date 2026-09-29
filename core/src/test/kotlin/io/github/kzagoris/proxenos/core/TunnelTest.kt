package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.engine.EmbeddedServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * The Connected state machine (SPEC §8.4, §13.1), driven the way §13.1 says it must be: a fake
 * `/metrics` responder on a Unix socket with a gauge value the test controls, and a stub script
 * standing in for `tunnel-client` (ADR 0002). The stub does what the real one does at the edges
 * this reads — it writes its health base URL to `--health.url-file` and keeps running — and
 * forwards whatever the test appends to its log file as its own output, which is how a test
 * makes the tunnel complain.
 *
 * Staleness is real time passing since the gauge last moved, so the poll cycle is shortened here
 * the way the command budget is elsewhere (ADR 0002): a 1s wait, a 1s guardrail and a half-second
 * margin, where the Runtime would use 30s, 5s and 15s.
 */
class TunnelTest {
  @TempDir
  lateinit var temporary: Path

  private val out: Path get() = temporary.resolve("stub")
  private val tunnels = mutableListOf<Tunnel>()
  private lateinit var metrics: EmbeddedServer<*, *>
  private val scope = CoroutineScope(Dispatchers.Default)

  /** What `/metrics` reports as the last successful poll: `null` leaves the line out. */
  @Volatile private var gauge: Double? = null
  /** What `/health/control-plane` answers: `null` is v0.0.14's 404. */
  @Volatile private var controlPlane: String? = null
  private val asked = Collections.synchronizedList(mutableListOf<String>())
  private val heard = Collections.synchronizedList(mutableListOf<String>())

  private fun config(pollTimeout: Duration = WAIT) = RuntimeConfig(
    stateDirectory = temporary.resolve("state"),
    mcpSocket = temporary.resolve("mcp.sock"),
    controlSocket = temporary.resolve("control.sock"),
    tunnelExecutable = temporary.resolve("bin/tunnel-client"),
    killGrace = 500.milliseconds,
    tunnelPollTimeout = pollTimeout,
    tunnelPollGuardrail = GUARDRAIL,
    tunnelStalenessMargin = MARGIN,
    tunnelReadInterval = READ,
  )

  @BeforeTest
  fun `a machine with a stub tunnel-client and a fake metrics listener`() {
    Files.createDirectories(out)
    Files.createDirectories(temporary.resolve("state"))
    val stub = temporary.resolve("bin/tunnel-client")
    Files.createDirectories(stub.parent)
    Files.writeString(
      stub,
      """
      #!/bin/sh
      out='$out'
      n=${'$'}(( ${'$'}(cat "${'$'}out/starts" 2>/dev/null || echo 0) + 1 ))
      echo "${'$'}n" > "${'$'}out/starts"
      printf '%s\n' "${'$'}@" > "${'$'}out/args"
      while [ ${'$'}# -gt 0 ]; do
        case "${'$'}1" in
          --health.unix-socket) socket="${'$'}2"; shift ;;
          --health.url-file) url_file="${'$'}2"; shift ;;
        esac
        shift
      done
      printf 'http+unix://%s' "${'$'}(printf '%s' "${'$'}socket" | base64 -w0 | tr '+/' '-_' | tr -d '=')" > "${'$'}url_file"
      echo "${'$'}${'$'}" > "${'$'}out/pid.${'$'}n"
      if [ "${'$'}n" = 1 ] && [ -f "${'$'}out/die-first" ]; then exit 7; fi
      touch "${'$'}out/log"
      exec tail -n +1 -F "${'$'}out/log"
      """.trimIndent() + "\n",
    )
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"))

    metrics = embeddedServer(CIO, configure = { unixConnector(config().tunnelHealthSocket.toString()) }) {
      routing {
        get("/metrics") {
          asked += call.request.path()
          call.respondText(metricsText(gauge))
        }
        // What the other two candidate signals answer, which is "fine" whatever the poller does.
        get("/healthz") { asked += call.request.path(); call.respondText("live") }
        get("/readyz") { asked += call.request.path(); call.respondText("ready") }
        get("/health/control-plane") {
          asked += call.request.path()
          controlPlane?.let { call.respondText(it, ContentType.Application.Json) } ?: call.respondText("404 page not found", status = HttpStatusCode.NotFound)
        }
      }
    }.start(wait = false)
  }

  @AfterTest
  fun `nothing this test started outlives it`() {
    scope.cancel()
    tunnels.forEach(Tunnel::stop)
    metrics.stop(0, 0)
    Files.list(out).use { files ->
      files.filter { it.fileName.toString().matches(Regex("pid\\.\\d+")) }.forEach {
        ProcessHandle.of(Files.readString(it).trim().toLong()).ifPresent(ProcessHandle::destroyForcibly)
      }
    }
  }

  // Scenario 5: the link is lost, and then restored.
  @Test
  fun `a link that is lost reads Failed quoting the tunnel, and one that comes back reads Connected with no act`() {
    val tunnel = started()
    val seen = transitions(tunnel)
    // Absent, zero, and older than this Start are one rule: no poll has succeeded yet.
    assertStaysIn<RuntimeState.Connecting>(tunnel)
    gauge = 0.0
    assertStaysIn<RuntimeState.Connecting>(tunnel)
    gauge = now() - 3_600
    assertStaysIn<RuntimeState.Connecting>(tunnel)

    val polled = succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    // A healthy idle tunnel: its gauge is a whole wait plus a guardrail old before the long poll
    // returns, which on the default cycle is the ~35s a hardcoded threshold would call lost.
    sleepUntil(polled + WAIT + GUARDRAIL)
    assertIs<RuntimeState.Connected>(tunnel.status().state)

    complain(FORBIDDEN)
    val failed = awaitState<RuntimeState.Failed>(tunnel)
    assertEquals(
      TunnelComplaint(
        statusCode = 401,
        errorCode = "tunnel_use_forbidden",
        mitigation = "Verify the tunnel ID and that the API key has permission to use this tunnel.",
        message = "Tunnel use forbidden for this key.",
      ),
      failed.complaint,
      "the tunnel's own words, verbatim, and nothing added",
    )

    // The retry window: the tunnel keeps failing and keeps saying so, the Runtime keeps reading,
    // and nothing is emitted, because none of it is a transition.
    val before = seen.size
    repeat(3) { complain(TRANSPORT) }
    assertStaysIn<RuntimeState.Failed>(tunnel)
    assertEquals(before, seen.size, "an event during the retry window: ${seen.drop(before)}")

    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    // Connecting is one-way, even when a restarted child's gauge reads zero again.
    gauge = 0.0
    assertStaysIn<RuntimeState.Connected>(tunnel)

    assertEquals(
      listOf(
        RuntimeState.Connecting::class, RuntimeState.Connected::class,
        RuntimeState.Failed::class, RuntimeState.Connected::class,
      ),
      seen.map { it.state::class },
      "one event per transition, and nothing else",
    )
    assertTrue(seen.zipWithNext().all { (a, b) -> !b.enteredAt.isBefore(a.enteredAt) })
    assertEquals(1, starts(), "a child that is alive with a failing poller was restarted")
  }

  @Test
  fun `a failure the tunnel gave no words for is Failed with nothing quoted`() {
    val tunnel = started()
    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    assertNull(awaitState<RuntimeState.Failed>(tunnel).complaint)
  }

  @Test
  fun `words from before the last success are not quoted as the reason the link was lost`() {
    val tunnel = started()
    complain(FORBIDDEN)
    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    assertNull(awaitState<RuntimeState.Failed>(tunnel).complaint)
  }

  @Test
  fun `the staleness threshold is the child's own poll cycle, so a longer poll wait is not a false Failed`() {
    val tunnel = started(config(pollTimeout = 4.seconds))
    awaitFile("pid.1")
    assertEquals("4s", argument("--control-plane.poll-timeout"), "the child polls with the wait the threshold assumes")
    assertEquals("1s", argument("--control-plane.poll-deadline-guardrail"))
    val polled = succeeded()
    awaitState<RuntimeState.Connected>(tunnel)

    // Stale on the 1s wait the other tests use, and inside one cycle of this one.
    sleepUntil(polled + 4.seconds)
    assertIs<RuntimeState.Connected>(tunnel.status().state)
    awaitState<RuntimeState.Failed>(tunnel)
    assertTrue(now() - polled >= (4.seconds + GUARDRAIL + MARGIN).inWholeMilliseconds / 1000.0, "Failed inside one cycle")
  }

  @Test
  fun `neither healthz nor readyz is asked, however long the poller fails`() {
    val tunnel = started()
    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    awaitState<RuntimeState.Failed>(tunnel)
    assertTrue(asked.isNotEmpty())
    assertEquals(setOf("/metrics"), asked.toSet())
  }

  // Scenario 13, third part: a valid credentials file whose key is wrong.
  @Test
  fun `a rejected key reads Connecting forever, with the tunnel's own 401 beside it after one long-poll wait`() {
    // A longer wait than the other tests', so there is room to look before it has passed.
    val wait = 3.seconds
    val startedAt = now()
    val tunnel = started(config(pollTimeout = wait))
    val seen = transitions(tunnel)
    complain(FORBIDDEN)
    assertStaysIn<RuntimeState.Connecting>(tunnel)
    assertNull(tunnel.connectingWords().value, "words before one long-poll wait had passed")

    assertTrue(eventually(10.seconds) { tunnel.connectingWords().value != null }, "no words beside Connecting")
    assertTrue(now() >= startedAt + wait + GUARDRAIL, "words before one long-poll wait had passed")
    assertEquals(
      ConnectingWords(
        TunnelComplaint(
          statusCode = 401,
          errorCode = "tunnel_use_forbidden",
          mitigation = "Verify the tunnel ID and that the API key has permission to use this tunnel.",
          message = "Tunnel use forbidden for this key.",
        ),
        failureCategory = null,
      ),
      tunnel.connectingWords().value,
      "the tunnel's own words verbatim; a 404 from /health/control-plane leaves nothing in their place",
    )

    repeat(3) { complain(FORBIDDEN) }
    assertStaysIn<RuntimeState.Connecting>(tunnel)
    assertEquals(listOf(RuntimeState.Connecting::class), seen.map { it.state::class }, "the words made a transition")
    assertEquals(1, asked.count { it == "/health/control-plane" }, "probed more than once")
    assertFalse(heard.any { "control-plane" in it && "404" in it }, "a 404 from the feature probe was reported as an error")
  }

  @Test
  fun `where the tunnel answers health control-plane, its failure category is carried, and a success retires the words`() {
    controlPlane = """{"schema_version":1,"snapshot_at":"2026-09-23T12:00:00Z","component":"control-plane","status":"degraded",""" +
      """"state":"failing","limited":false,"details":{"consecutive_failures":7,"current_poll_age_seconds":0,""" +
      """"configured_wait_seconds":1,"effective_wait_seconds":1,"deadline_seconds":2,"failure_category":"http_error","http_status":401}}"""
    val tunnel = started()
    complain(FORBIDDEN)
    assertTrue(eventually(10.seconds) { tunnel.connectingWords().value != null }, "no words beside Connecting")
    assertEquals("http_error", tunnel.connectingWords().value?.failureCategory)
    assertIs<RuntimeState.Connecting>(tunnel.status().state, "the probe is not a state input")

    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    assertNull(tunnel.connectingWords().value, "words beside a link that works")
    assertEquals(1, asked.count { it == "/health/control-plane" })
  }

  @Test
  fun `a child that dies is started again, and the link is judged across it`() {
    Files.createFile(out.resolve("die-first"))
    val tunnel = started()
    awaitFile("pid.2")
    assertTrue(eventually(5.seconds) { !alive("pid.1") } && alive("pid.2"))
    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
  }

  @Test
  fun `Disconnect takes the link down and leaves the Runtime, its registrations and running work alone`() = runBlocking<Unit> {
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(temporary.resolve("state/registry.properties"), feed)
    val activity = Activity(temporary.resolve("state/activity"), feed = feed)
    val pipeline = WorkspaceOperationsPipeline(
      registry, activity, Operation.SEARCH_BUDGET, PathLocks(), GitTools(),
      commandBudget = 30.seconds, runner = CommandRunner(grace = 500.milliseconds),
    )
    val tunnel = started()
    val management: WorkspaceManagement = RuntimeManagement(
      registry, activity, tunnel, pipeline,
      ConnectorAcknowledgement(temporary.resolve("state/connector"), feed), feed,
    )
    val root = Files.createDirectory(temporary.resolve("project"))
    val workspace = management.perform(ManagementAct.Register(root.toString(), "api"))
    management.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
    val child = ProcessHandle.of(Files.readString(out.resolve("pid.1")).trim().toLong()).orElseThrow()

    val running = async(Dispatchers.IO) {
      pipeline.operationsFor(Origin.ChatGpt).perform(Operation.RunCommand("api", "sleep 1; echo finished", deliveryKey = "k"))
    }
    assertTrue(eventually(5.seconds) { activity.entries().any { it.tool == "run_command" && it.elapsed == null } }, "the command never started")

    management.perform(ManagementAct.Disconnect)

    assertEquals(RuntimeState.Disconnected, tunnel.status().state)
    assertFalse(child.isAlive, "the tunnel child outlived Disconnect")
    assertEquals(listOf("api" to AccessLevel.Command), registry.listings().map { it.name to it.accessLevel })
    val reply = assertIs<CommandReply.Finished>(assertIs<Outcome.Ok<CommandReply>>(running.await()).value)
    assertEquals("finished\n", reply.result.output, "Disconnect stopped running work")
    // Disconnected is the user's word, not a measurement: a fresh gauge does not overrule it,
    // and no child is started behind it.
    assertStaysIn<RuntimeState.Disconnected>(tunnel)
    assertEquals(1, starts())

    management.perform(ManagementAct.Connect)
    awaitFile("pid.2")
    // Connecting again: nothing has been measured since the user asked for the link back.
    assertEquals(RuntimeState.Connecting, tunnel.status().state)
    succeeded()
    awaitState<RuntimeState.Connected>(tunnel)
  }

  @Test
  fun `connect-intent survives a restart, and Connected state does not`() = runBlocking<Unit> {
    val first = started()
    succeeded()
    awaitState<RuntimeState.Connected>(first)
    first.disconnect()
    first.stop()

    val second = started()
    assertStaysIn<RuntimeState.Disconnected>(second)
    assertEquals(1, starts(), "a Runtime that was last Disconnected started a tunnel")
    second.connect()
    awaitFile("pid.2")
    succeeded()
    awaitState<RuntimeState.Connected>(second)
    second.stop()

    // The gauge still reads a success, but one from before this Start.
    val third = started()
    awaitFile("pid.3")
    assertStaysIn<RuntimeState.Connecting>(third)
  }

  private fun started(config: RuntimeConfig = config()): Tunnel =
    Tunnel(config, "http://proxenos.internal/mcp", TunnelCredentials("tunnel_abc", "sk-secret")) { heard += it }
      .also { tunnels += it; it.start() }

  /** Every status the stream carries, in order, starting from the current one. */
  private fun transitions(tunnel: Tunnel): List<RuntimeStatus> {
    val seen = Collections.synchronizedList(mutableListOf<RuntimeStatus>())
    scope.launch { tunnel.observe().collect { seen += it } }
    assertTrue(eventually(5.seconds) { seen.isNotEmpty() })
    return seen
  }

  /** Appends [line] to the stub's output and waits until the Runtime has heard it. */
  private fun complain(line: String) {
    val before = heard.count { line in it }
    Files.writeString(out.resolve("log"), line + "\n", CREATE, APPEND)
    assertTrue(eventually(10.seconds) { heard.count { line in it } > before }, "the tunnel's line never reached the Runtime")
  }

  private inline fun <reified S : RuntimeState> awaitState(tunnel: Tunnel): S {
    assertTrue(
      eventually(10.seconds) { tunnel.status().state is S },
      "never ${S::class.simpleName}; stayed ${tunnel.status()}\n${heard.joinToString("\n")}",
    )
    return tunnel.status().state as S
  }

  /** Several reads' worth, and the state has not moved. A Disconnected link is not read at all. */
  private inline fun <reified S : RuntimeState> assertStaysIn(tunnel: Tunnel) {
    val reads = asked.size
    if (tunnel.status().state != RuntimeState.Disconnected) {
      assertTrue(eventually(10.seconds) { asked.size >= reads + 3 }, "the gauge is not being read")
    }
    Thread.sleep((READ * 3).inWholeMilliseconds)
    assertIs<S>(tunnel.status().state)
  }

  private fun alive(pidFile: String): Boolean =
    ProcessHandle.of(Files.readString(out.resolve(pidFile)).trim().toLong()).map(ProcessHandle::isAlive).orElse(false)

  private fun starts(): Int = Files.readString(out.resolve("starts")).trim().toInt()

  private fun argument(flag: String): String = Files.readAllLines(out.resolve("args")).let { it[it.indexOf(flag) + 1] }

  private fun awaitFile(name: String) {
    assertTrue(eventually(10.seconds) { Files.exists(out.resolve(name)) }, "no $name from the stub:\n${heard.joinToString("\n")}")
  }

  private fun now(): Double = System.currentTimeMillis() / 1000.0

  /** A poll succeeds now, and then none does: the gauge stops where it is. */
  private fun succeeded(): Double = now().also { gauge = it }

  private fun sleepUntil(unixSeconds: Double) {
    val left = ((unixSeconds - now()) * 1000).toLong()
    if (left > 0) Thread.sleep(left)
  }

  private operator fun Double.plus(duration: Duration): Double = this + duration.inWholeMilliseconds / 1000.0

  private fun eventually(within: Duration, condition: () -> Boolean): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + within
    while (!condition()) {
      if (deadline.hasPassedNow()) return false
      Thread.sleep(20)
    }
    return true
  }

  private companion object {
    val READ = 50.milliseconds
    val WAIT = 1.seconds
    val GUARDRAIL = 1.seconds
    val MARGIN = 500.milliseconds

    /** The shape `tunnel-client` v0.0.14 serves, gauge line and all, from a real run. */
    fun metricsText(gauge: Double?): String = buildString {
      appendLine("# HELP commands_poll_cycles_total Total number of poll cycles initiated by the poller.")
      appendLine("# TYPE commands_poll_cycles_total counter")
      appendLine("""commands_poll_cycles_total{otel_scope_name="controlplane",otel_scope_schema_url="",otel_scope_version=""} 10""")
      if (gauge != null) {
        appendLine("# HELP commands_poll_last_successful_timestamp_seconds Unix timestamp in seconds of the last successful poll.")
        appendLine("# TYPE commands_poll_last_successful_timestamp_seconds gauge")
        appendLine("""commands_poll_last_successful_timestamp_seconds{otel_scope_name="controlplane",otel_scope_schema_url="",otel_scope_version=""} $gauge""")
      }
    }

    /** A rejected poll, in the shape `tunnel-client --log.format json` writes it. */
    const val FORBIDDEN = """{"time":"2026-09-22T22:16:21+03:00","level":"WARN","msg":"poll failed; backing off","component":"controlplane","error":"controlplane client: unexpected status 401: tunnel_use_forbidden","retry_in_ms":10000,"status_code":401,"status":"401 Unauthorized","error_code":"tunnel_use_forbidden","error_message":"Tunnel use forbidden for this key.","mitigation":"Verify the tunnel ID and that the API key has permission to use this tunnel."}"""

    /** A poll that never reached the control plane: no status, no code, nothing to mitigate. */
    const val TRANSPORT = """{"time":"2026-09-22T22:17:00+03:00","level":"WARN","msg":"poll failed; backing off","component":"controlplane","error":"Get \"https://api.openai.com/v1/tunnels/tunnel_abc/poll\": dial tcp: lookup api.openai.com: no such host","retry_in_ms":8000}"""
  }
}

/**
 * A [Tunnel] that is never started, for tests of management acts that do not touch the link:
 * it launches nothing and reads nothing until [Tunnel.start], which these never call.
 */
internal fun unstartedTunnel(stateDirectory: Path): Tunnel = Tunnel(
  RuntimeConfig(stateDirectory, stateDirectory.resolve("mcp.sock"), stateDirectory.resolve("control.sock"), tunnelExecutable = Path.of("/bin/false")),
  "http://proxenos.internal/mcp", TunnelCredentials("tunnel_abc", "sk-secret"),
) {}
