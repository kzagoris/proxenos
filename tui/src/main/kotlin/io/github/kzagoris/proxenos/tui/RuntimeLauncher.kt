package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.control.ControlSocket
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Why the Runtime could not be started, in the words that came back. */
class StartFailed(message: String) : Exception(message)

/**
 * Finds the Runtime on the control socket, and starts it when it is not there.
 *
 * A Runtime started here outlives this frontend — closing the TUI leaves exposure exactly as it
 * was — so it is started in a session of its own, with no terminal: a Ctrl-C or a closed window
 * reaches this process and not that one.
 */
class RuntimeLauncher(
  val socket: Path,
  /** The `runtime` launcher to start, or null where none was found; [start] then says how to name one. */
  private val executable: Path?,
  /** Where a started Runtime's standard error goes, since it has no terminal to write to. */
  private val log: Path = socket.resolveSibling("runtime.log"),
  private val patience: Duration = 20.seconds,
) {
  /** Whether a Runtime is answering on [socket] now. */
  fun running(): Boolean = try {
    SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
    true
  } catch (_: IOException) {
    false
  }

  /**
   * Starts the Runtime and returns once it answers. A Runtime that refuses to start says why on
   * standard error and exits, and that is what [StartFailed] carries.
   */
  suspend fun start() = withContext(Dispatchers.IO) {
    val program = executable ?: throw StartFailed(
      "cannot find the Runtime to start. Set $RUNTIME_VARIABLE to its launcher (runtime/build/install/runtime/bin/runtime).",
    )
    ownerOnly(log.parent)
    val from = if (Files.exists(log)) Files.size(log) else 0L
    val process = try {
      ProcessBuilder("setsid", program.toString())
        // Told outright, so a socket this frontend was given with --control-socket is the one
        // the Runtime binds, rather than the one it would resolve for itself.
        .apply { environment()[ControlSocket.VARIABLE] = socket.toString() }
        .redirectInput(File("/dev/null"))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .redirectErrorStream(true)
        .start()
    } catch (failed: IOException) {
      throw StartFailed("could not start $program: ${failed.message}")
    }
    val deadline = System.nanoTime() + patience.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
      if (running()) return@withContext
      if (!process.isAlive && !running()) throw StartFailed(said(from) ?: "the Runtime exited with status ${process.exitValue()} and said nothing.")
      delay(100.milliseconds)
    }
    throw StartFailed("the Runtime did not answer on $socket within $patience. What it said is in $log.")
  }

  /** What the Runtime wrote to [log] since [from], without its `runtime:` prefix. */
  private fun said(from: Long): String? {
    val bytes = try {
      Files.readAllBytes(log)
    } catch (_: IOException) {
      return null
    }
    return String(bytes, from.toInt().coerceAtMost(bytes.size), bytes.size - from.toInt().coerceAtMost(bytes.size))
      .lines().map { it.removePrefix("runtime: ").trim() }.filter { it.isNotEmpty() }
      .joinToString(" ").ifEmpty { null }
  }

  private fun ownerOnly(directory: Path) {
    if (Files.isDirectory(directory)) return
    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
  }

  companion object {
    const val RUNTIME_VARIABLE = "PROXENOS_RUNTIME"
    /**
     * `PROXENOS_RUNTIME`, else a `runtime` launcher in the `bin/` beside this one's
     * installation — which is where one distribution of both would put it.
     */
    fun executableFrom(environment: Map<String, String>): Path? {
      environment[RUNTIME_VARIABLE]?.let { return Path.of(it) }
      val installed = runCatching {
        Path.of(RuntimeLauncher::class.java.protectionDomain.codeSource.location.toURI()).parent?.resolveSibling("bin")?.resolve("runtime")
      }.getOrNull()
      return installed?.takeIf { Files.isExecutable(it) }
    }
  }
}
