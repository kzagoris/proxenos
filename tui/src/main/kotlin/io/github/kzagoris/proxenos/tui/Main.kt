package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.control.ConfigRefused
import io.github.kzagoris.proxenos.control.ControlSocket
import io.github.kzagoris.proxenos.control.NotSent
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/**
 * `tui` attaches to the Runtime — starting it first when it is not running — and draws the
 * home screen. `tui start` and `tui stop` do the one thing each says and exit. `tui --dump`
 * draws each frame as plain lines on standard output with no terminal needed.
 *
 * A frontend and nothing more: everything here goes through [WorkspaceManagement]
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
  val socket = try {
    ControlSocket.resolve(environment, flag = options.socket)
  } catch (refused: ConfigRefused) {
    System.err.println("tui: ${refused.message} Or name the control socket with --control-socket.")
    exitProcess(78)
  }
  val attachment = RuntimeAttachment(socket, RuntimeAttachment.executable(environment))
  when (options.command) {
    "start" -> exitProcess(start(attachment, socket))
    "stop" -> exitProcess(stop(attachment.management))
    null -> if (options.dump) exitProcess(dump(attachment, options)) else interactive(attachment)
  }
}

/** Keep the launch command and debugger output outside the dashboard, restoring them on exit. */
private fun interactive(attachment: RuntimeAttachment) {
  val alternate = System.console() != null && System.getenv("TERM") != "dumb"
  if (alternate) {
    print("\u001b[?1049h\u001b[H")
    System.out.flush()
  }
  try {
    runHome(attachment)
  } finally {
    if (alternate) {
      print("\u001b[?1049l")
      System.out.flush()
    }
  }
}

/**
 * Starts the Runtime when nothing answers, and leaves once something does. It never attaches:
 * starting is not looking, and a start should not wait on what the Runtime has to say.
 */
private fun start(attachment: RuntimeAttachment, socket: Path): Int = runBlocking {
  if (attachment.answering()) return@runBlocking 0.also { println("The Runtime is already running on $socket.") }
  val refused = attachment.start() ?: return@runBlocking 0.also {
    println("The Runtime is running on $socket. There is no autostart: after a reboot, start it again.")
  }
  System.err.println("tui: the Runtime ${refused.words}")
  1
}

private fun stop(management: WorkspaceManagement): Int {
  try {
    runBlocking { management.perform(ManagementAct.Stop) }
  } catch (_: NotSent) {
    return 0.also { println("The Runtime is not running.") }
  }
  println("The Runtime stopped. Every Operation it was running is left Uncertain; registrations survive.")
  return 0
}

/**
 * One frame per event on the stream — the snapshot, then each change — as plain lines, until
 * the stream ends or [Options.frames] have been drawn. It never starts the Runtime: it is for
 * looking, and a look that started something would not be one.
 */
private fun dump(attachment: RuntimeAttachment, options: Options): Int {
  val frame = { Frame(columns = options.columns, rows = options.rows) }
  val management = attachment.management
  var home = Home()
  var drawn = 0
  runBlocking {
    // Stops at the frame limit, or when the stream ends: Absent is always the last value.
    attachment.attach(startIfAbsent = false).firstOrNull { next ->
      home = home.observed(next)
      when (next) {
        is Attachment.Attached -> {
          // Each band row's line is read as the screen draws it, from the buffer get_result reads.
          // A Runtime gone between the event and the read leaves the line unread; the stream's end
          // follows and is said as such.
          for (running in home.band) {
            val output = try {
              management.perform(ManagementAct.ReadOutput(running.entry))
            } catch (_: IOException) {
              null
            }
            home = home.read(running.entry, output)
          }
          println("=== frame ${++drawn} ===")
          render(home, frame()).forEach { println(it.plain.trimEnd()) }
          System.out.flush()
          drawn == options.frames
        }
        is Attachment.Absent -> {
          if (drawn == 0) render(home, frame()).forEach { println(it.plain.trimEnd()) }
          else println("=== the Runtime closed the stream ===")
          true
        }
        else -> false
      }
    }
  }
  return if (drawn == 0) 1 else 0
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
