package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.control.ControlSocket
import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withContext

/**
 * A frontend's link to the Runtime on [socket]: the acts, through [management], and the stream,
 * through [attach] — which starts the Runtime first, through [start], when it is asked to and
 * nothing answers.
 *
 * A Runtime started here outlives the frontend that started it — closing one leaves exposure
 * exactly as it was — so it is started in a session of its own, with no terminal: a Ctrl-C or a
 * closed window reaches the frontend and not the Runtime.
 */
class RuntimeAttachment(
  private val socket: Path,
  /** The `runtime` launcher to start, or null where nothing named one; a start then says how to name one. */
  private val executable: Path?,
  /** How long a started Runtime has to answer before the start is reported as failed. */
  private val patience: Duration = 20.seconds,
) {
  /** Where a started Runtime's output goes, since it has no terminal to write to. */
  private val log: Path = socket.resolveSibling("runtime.log")

  /** The acts, over the control socket: every one dials its own connection. */
  val management: WorkspaceManagement = ManagementClient(socket)

  /**
   * The launch policy both frontends share: opening one starts an absent Runtime. It lives here
   * rather than in either frontend so the two cannot come to disagree about it.
   */
  fun open(): Flow<Attachment> = attach(startIfAbsent = true)

  /**
   * [Attachment.Starting] only when nothing answers and [startIfAbsent] allows a start, then
   * [Attachment.Attaching], then [Attachment.Attached] on every event, each change folded onto
   * the snapshot. When the stream ends, for any cause, [Attachment.Absent] and the flow completes:
   * there is no re-attach and no restart behind a frontend's back. Attaching again is collecting
   * again.
   */
  fun attach(startIfAbsent: Boolean): Flow<Attachment> = flow {
    if (!answering()) {
      if (!startIfAbsent) return@flow emit(Attachment.Absent(Reason.NotAnswering))
      emit(Attachment.Starting)
      start()?.let { refused -> return@flow emit(Attachment.Absent(refused)) }
    }
    emit(Attachment.Attaching)
    emitAll(
      management.observe()
        .runningFold<RuntimeEvent, RuntimeEvent.Snapshot?>(null) { snapshot, event ->
          when (event) {
            is RuntimeEvent.Snapshot -> event
            // A change before any snapshot has nothing to apply to; the snapshot comes first.
            is RuntimeEvent.Change -> snapshot?.after(event)
          }
        }
        .filterNotNull()
        .map { Attachment.Attached(it) }
        // Upstream only: a stream that ends by failing ends all the same, and what failed is not
        // the frontend's to diagnose. A collector's own failure is not caught here.
        .catch { },
    )
    emit(Attachment.Absent(Reason.NotAnswering))
  }

  /** Whether a Runtime is answering on [socket] now. */
  suspend fun answering(): Boolean = withContext(Dispatchers.IO) {
    try {
      SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
      true
    } catch (_: IOException) {
      false
    }
  }

  /**
   * Starts the Runtime unless one answers already, and returns null once it answers, else why
   * not. Answering is all it waits for: it never dials for the stream, so a start does not hang on
   * a Runtime that accepts and does not speak, nor read a snapshot it would only throw away. A
   * Runtime that refuses to start says why on standard error and exits, and its words are what
   * come back.
   */
  suspend fun start(): Reason.StartFailed? = withContext(Dispatchers.IO) {
    if (answering()) null else spawn()?.let(Reason::StartFailed)
  }

  private suspend fun spawn(): String? {
    val program = executable ?: return "cannot find the Runtime to start: neither $VARIABLE nor the $PROPERTY " +
      "property the launcher scripts set names it. $SET_VARIABLE"
    // setsid would start and then fail to run it, and say so only in the log. A bare name is
    // left to setsid, which looks it up on PATH as any exec does.
    if ('/' in program.toString() && !Files.isExecutable(program)) {
      return "cannot start $program: there is no executable file there. $SET_VARIABLE"
    }
    val from: Long
    val process = try {
      ownerOnly(log.parent)
      from = if (Files.exists(log)) Files.size(log) else 0L
      ProcessBuilder("setsid", program.toString())
        // Told outright, so a socket this frontend was given with --control-socket is the one
        // the Runtime binds, rather than the one it would resolve for itself.
        .apply { environment()[ControlSocket.VARIABLE] = socket.toString() }
        .redirectInput(File("/dev/null"))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .redirectErrorStream(true)
        .start()
    } catch (failed: IOException) {
      return "could not start $program: ${failed.message}"
    }
    val deadline = System.nanoTime() + patience.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
      if (answering()) return null
      if (!process.isAlive && !answering()) return said(from) ?: "the Runtime exited with status ${process.exitValue()} and said nothing."
      delay(100.milliseconds)
    }
    return "the Runtime did not answer on $socket within $patience. What it said is in $log."
  }

  /** What the Runtime wrote to [log] since [from], without its `runtime:` prefix. */
  private fun said(from: Long): String? {
    val words = try {
      Files.newByteChannel(log).use { channel ->
        channel.position(from.coerceAtMost(channel.size()))
        Channels.newInputStream(channel).bufferedReader().use { it.readText() }
      }
    } catch (_: IOException) {
      return null
    }
    return words
      .lines().map { it.removePrefix("runtime: ").trim() }.filter { it.isNotEmpty() }
      .joinToString(" ").ifEmpty { null }
  }

  private fun ownerOnly(directory: Path) {
    if (Files.isDirectory(directory)) return
    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
  }

  companion object {
    const val VARIABLE = "PROXENOS_RUNTIME"

    /**
     * Set by `bin/tui` to the `bin/runtime` of its own tree, resolved through any symlink to the
     * launcher, so the answer does not depend on where this jar or the working directory is.
     */
    const val PROPERTY = "proxenos.runtime"

    private const val SET_VARIABLE = "Set $VARIABLE to the Runtime's launcher (runtime/build/install/runtime/bin/runtime)."

    /** The `runtime` launcher to start: [VARIABLE] first, else [PROPERTY]; null when neither names one. */
    fun executable(
      environment: Map<String, String> = System.getenv(),
      property: String? = System.getProperty(PROPERTY),
    ): Path? = (environment[VARIABLE]?.takeIf { it.isNotEmpty() } ?: property?.takeIf { it.isNotEmpty() })?.let(Path::of)
  }
}
