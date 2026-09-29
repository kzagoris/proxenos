package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

/**
 * `tui` attaches to the Runtime — starting it first when it is not running — and draws the
 * home screen. `tui start` and `tui stop` do the one thing each says and exit. `tui --dump`
 * draws each frame as plain lines on standard output with no terminal needed.
 *
 * A frontend and nothing more (SPEC §1): everything here goes through [WorkspaceManagement]
 * over the control socket, never the core, so quitting leaves the Runtime and every Workspace
 * exactly as they were.
 */
fun main(args: Array<String>) {
  val options = try {
    Options.parse(args.toList())
  } catch (usage: IllegalArgumentException) {
    System.err.println("tui: ${usage.message}\n$USAGE")
    exitProcess(64)
  }
  val environment = System.getenv()
  val socket = options.socket ?: RuntimeLauncher.socketFrom(environment) ?: run {
    System.err.println("tui: XDG_RUNTIME_DIR is not set, so the control socket cannot be found. Name it with --control-socket.")
    exitProcess(78)
  }
  val launcher = RuntimeLauncher(socket, RuntimeLauncher.executableFrom(environment))
  val management: WorkspaceManagement = ManagementClient(socket)
  when (options.command) {
    "start" -> exitProcess(start(launcher))
    "stop" -> exitProcess(stop(launcher, management))
    null -> if (options.dump) exitProcess(dump(management, launcher, options)) else interactive(management, launcher)
  }
}

/** Keep the launch command and debugger output outside the dashboard, restoring them on exit. */
private fun interactive(management: WorkspaceManagement, launcher: RuntimeLauncher) {
  val alternate = System.console() != null && System.getenv("TERM") != "dumb"
  if (alternate) {
    print("\u001b[?1049h\u001b[H")
    System.out.flush()
  }
  try {
    runHome(management, launcher)
  } finally {
    if (alternate) {
      print("\u001b[?1049l")
      System.out.flush()
    }
  }
}

private fun start(launcher: RuntimeLauncher): Int {
  if (launcher.running()) return 0.also { println("The Runtime is already running on ${launcher.socket}.") }
  return try {
    runBlocking { launcher.start() }
    println("The Runtime is running on ${launcher.socket}. There is no autostart: after a reboot, start it again.")
    0
  } catch (failed: StartFailed) {
    System.err.println("tui: the Runtime ${failed.message}")
    1
  }
}

private fun stop(launcher: RuntimeLauncher, management: WorkspaceManagement): Int {
  if (!launcher.running()) return 0.also { println("The Runtime is not running.") }
  runBlocking { management.perform(ManagementAct.Stop) }
  println("The Runtime stopped. Every Operation it was running is left Uncertain; registrations survive.")
  return 0
}

/**
 * One frame per event on the stream — the snapshot, then each change — as plain lines, until
 * the stream ends or [Options.frames] have been drawn. It never starts the Runtime: it is for
 * looking, and a look that started something would not be one.
 */
private fun dump(management: WorkspaceManagement, launcher: RuntimeLauncher, options: Options): Int {
  val frame = { Frame(columns = options.columns, rows = options.rows) }
  if (!launcher.running()) {
    render(Home().detached(NOT_RUNNING), frame()).forEach { println(it.plain.trimEnd()) }
    return 1
  }
  var home = Home()
  var drawn = 0
  return try {
    runBlocking {
      val events = management.observe()
      (if (options.frames != null) events.take(options.frames) else events).collect { event ->
        home = home.observed(event)
        // Each band row's line is read as the screen draws it, from the buffer get_result reads.
        for (running in home.band) home = home.read(running.entry, management.perform(ManagementAct.ReadOutput(running.entry)))
        println("=== frame ${++drawn} ===")
        render(home, frame()).forEach { println(it.plain.trimEnd()) }
        System.out.flush()
      }
    }
    0
  } catch (_: java.io.IOException) {
    println("=== the Runtime closed the stream ===")
    0
  }
}

private data class Options(
  val command: String? = null,
  val dump: Boolean = false,
  val frames: Int? = null,
  val columns: Int = 120,
  val rows: Int = Int.MAX_VALUE / 2,
  val socket: Path? = null,
) {
  companion object {
    fun parse(args: List<String>): Options {
      var options = Options()
      val rest = args.iterator()
      fun value(flag: String): String = if (rest.hasNext()) rest.next() else throw IllegalArgumentException("$flag needs a value.")
      fun number(flag: String): Int = value(flag).toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag needs a positive number.")
      while (rest.hasNext()) {
        options = when (val argument = rest.next()) {
          "start", "stop" -> if (options.command == null) options.copy(command = argument) else throw IllegalArgumentException("Unexpected argument: $argument")
          "--dump" -> options.copy(dump = true)
          "--frames" -> options.copy(frames = number(argument))
          "--columns" -> options.copy(columns = number(argument))
          "--rows" -> options.copy(rows = number(argument))
          "--control-socket" -> options.copy(socket = Path.of(value(argument)))
          else -> throw IllegalArgumentException("Unexpected argument: $argument")
        }
      }
      return options
    }
  }
}

private const val USAGE = "usage: tui [--control-socket <path>]                       the home screen; starts the Runtime if it is not running\n" +
  "       tui start | stop                                      start or stop the Runtime, then exit\n" +
  "       tui --dump [--frames <n>] [--columns <n>] [--rows <n>]  each frame as plain lines, without a terminal"
