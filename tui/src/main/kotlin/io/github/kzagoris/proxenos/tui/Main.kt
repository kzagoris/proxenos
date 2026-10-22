package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.control.ConfigRefused
import io.github.kzagoris.proxenos.control.ControlSocket
import io.github.kzagoris.proxenos.control.NotSent
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.LocalSetup
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
  val launcher = RuntimeAttachment.executable(environment)
  val attachment = RuntimeAttachment(socket, launcher)
  val local = LocalSetup(environment)
  when (options.command) {
    "start" -> exitProcess(start(attachment, socket))
    "stop" -> exitProcess(stop(attachment.management))
    "status" -> exitProcess(runBlocking { status(attachment, local, launcher, System.out) })
    "check" -> exitProcess(check(local, launcher, System.out))
    "await-listing" -> exitProcess(runBlocking { awaitListing(attachment, options.operand!!, options.timeout, System.out) })
    "register" -> exitProcess(runBlocking { register(attachment, options.operand!!, options.name, System.out) })
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
    println("The Runtime is running on $socket. Boot startup is a separate setting in the Runtime detail.")
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
  /** The directory `register` registers, or the Workspace `await-listing` waits on. */
  val operand: String? = null,
  val name: String? = null,
  val json: Boolean = false,
  val timeout: Duration = 5.minutes,
) {
  companion object {
    /** The one positional argument the two commands that take one are given; anything else is a mistake. */
    private fun unexpected(argument: String, options: Options): Options =
      if (options.command in listOf("await-listing", "register") && options.operand == null && !argument.startsWith("--")) options.copy(operand = argument)
      else throw IllegalArgumentException("Unexpected argument: $argument")

    fun parse(args: List<String>): Options {
      var options = Options()
      val rest = args.iterator()
      fun value(flag: String): String = if (rest.hasNext()) rest.next() else throw IllegalArgumentException("$flag needs a value.")
      fun number(flag: String): Int = value(flag).toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag needs a positive number.")
      while (rest.hasNext()) {
        options = when (val argument = rest.next()) {
          "start", "stop", "status", "check", "await-listing", "register" ->
            if (options.command == null) options.copy(command = argument) else unexpected(argument, options)
          "--name" -> options.copy(name = value(argument))
          "--json" -> options.copy(json = true)
          "--timeout" -> options.copy(timeout = number(argument).seconds)
          "--dump" -> options.copy(dump = true)
          "--frames" -> options.copy(frames = number(argument))
          "--columns" -> options.copy(columns = number(argument))
          "--rows" -> options.copy(rows = number(argument))
          "--control-socket" -> options.copy(socket = Path.of(value(argument)))
          else -> unexpected(argument, options)
        }
      }
      when (options.command) {
        "await-listing" -> options.operand ?: throw IllegalArgumentException("Name the Workspace to wait on.")
        "register" -> options.operand ?: throw IllegalArgumentException("Name the directory to register.")
        // The flag names the contract the caller reads (ADR 0013).
        "status" -> if (!options.json) throw IllegalArgumentException("status prints JSON only: tui status --json.")
      }
      return options
    }
  }
}

private const val USAGE = "usage: tui [--control-socket <path>]                       the home screen; starts the Runtime if it is not running\n" +
  "       tui start | stop                                      start or stop the Runtime, then exit\n" +
  "       tui --dump [--frames <n>] [--columns <n>] [--rows <n>]  each frame as plain lines, without a terminal\n" +
  "       tui status --json                                     the readings, Workspaces and local requirements, as JSON\n" +
  "       tui check                                             the local requirements; fails unless all are met\n" +
  "       tui await-listing <workspace> [--timeout <seconds>]   wait for ChatGPT to list the Workspace's Root\n" +
  "       tui register <directory> [--name <name>]              register a Workspace at Read in the running Runtime"
