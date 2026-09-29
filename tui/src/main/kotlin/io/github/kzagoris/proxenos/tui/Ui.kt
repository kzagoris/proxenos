package io.github.kzagoris.proxenos.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicMain
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The interactive home screen. Everything it shows is [render] of a [Home]; everything a key
 * does is [Home.press]. What is left here is the terminal, the stream, and carrying out the
 * [Command] a key asked for.
 */
fun runHome(management: WorkspaceManagement, launcher: RuntimeLauncher) = runMosaicMain {
  var home by remember { mutableStateOf(Home(attachment = if (launcher.running()) Attachment.Attaching else Attachment.Starting)) }
  // Bumped to attach again: after a start, or a Runtime that was not there the first time.
  var attempt by remember { mutableIntStateOf(0) }
  var quit by remember { mutableStateOf(false) }
  // What keys ask for, carried out by the effect below rather than by a remembered scope: a
  // remembered scope's Job never completes, and Mosaic ends a composition only once nothing in
  // it is still running — so it would outlive `q`.
  val commands = remember { Channel<Command>(Channel.UNLIMITED) }

  suspend fun start() {
    home = home.copy(attachment = Attachment.Starting)
    try {
      launcher.start()
      attempt++
    } catch (failed: StartFailed) {
      // Say it where [S] was pressed, not only in Review: a start that fails silently reads
      // as a key that did nothing.
      home = home.detached("refused to start: ${failed.message} · [S] try again")
        .say("The Runtime did not start. ${failed.message}", Tone.Bad)
    }
  }

  // Every effect is gated on this, so dropping them all is the exit. The Runtime is not touched
  // by quitting: closing a frontend leaves exposure exactly as it was.
  if (!quit) {
    LaunchedEffect(Unit) {
      // The Runtime is started when it is not running: the first thing this frontend does.
      if (home.attachment == Attachment.Starting) launch { start() }
      for (command in commands) launch { carryOut(command, management, { home }, { home = it }, ::start) }
    }

    // What each running command has printed, read again while the band has anything in it: the
    // buffer get_result reads, asked for rather than streamed, so a frontend nobody is looking
    // at costs the Runtime nothing. Keyed on what is running, so it stops when the band empties.
    val band = if (home.page == Page.Activity) home.band.map { it.entry } else emptyList()
    LaunchedEffect(band) {
      while (band.isNotEmpty()) {
        for (entry in band) {
          val output = try {
            management.perform(ManagementAct.ReadOutput(entry))
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            null
          }
          home = home.read(entry, output)
        }
        delay(OUTPUT_READ_INTERVAL)
      }
    }

    LaunchedEffect(attempt) {
      if (home.attachment == Attachment.Starting && attempt == 0) return@LaunchedEffect
      try {
        management.observe().collect { home = home.observed(it) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        home = home.detached(NOT_RUNNING)
      }
    }
  }

  val size = LocalTerminalState.current.size
  // A terminal that reports no size (a pty nobody sized) is drawn at a conventional one rather
  // than cut to nothing.
  val frame = Frame(columns = size.columns.takeIf { it > 0 } ?: 80, rows = size.rows.takeIf { it > 0 } ?: 24)
  val lines = render(home, frame)

  Column(
    modifier = Modifier.onKeyEvent { event ->
      if (event.ctrl && event.key == "c") {
        quit = true
        return@onKeyEvent true
      }
      val step = home.press(event.asKey(), frame)
      home = step.home
      when (val command = step.command) {
        null -> Unit
        Command.Quit -> quit = true
        else -> commands.trySend(command)
      }
      true
    },
  ) {
    for (line in lines) Line(line)
  }

}

/** One [Command] carried out, with what it leaves on the screen. */
private suspend fun carryOut(
  command: Command,
  management: WorkspaceManagement,
  home: () -> Home,
  update: (Home) -> Unit,
  start: suspend () -> Unit,
) {
  when (command) {
    Command.Quit -> Unit
    Command.StartRuntime -> start()
    is Command.Try -> update(
      try {
        val (said, tone) = Wording.tried(command.tool, command.workspace, management.perform(ManagementAct.TryOperation(command.op)))
        home().say(said, tone)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (refused: Exception) {
        home().say(refused.message ?: refused::class.simpleName.orEmpty(), Tone.Bad)
      },
    )
    is Command.Perform -> {
      update(
        try {
          management.perform(command.act)
          home().say(command.done)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (refused: Exception) {
          home().say(refused.message ?: refused::class.simpleName.orEmpty(), Tone.Bad)
        },
      )
      if (command.act == ManagementAct.Stop) update(home().detached(STOPPED))
    }
  }
}

/** Often enough that a build's last line reads as live; rarely enough to be nothing to the Runtime. */
private val OUTPUT_READ_INTERVAL = 1.seconds

internal const val NOT_RUNNING = "not running · [S] start it"
internal const val STOPPED = "stopped · registrations survive, nothing is exposed · [S] start it"

/** Mosaic names a shifted letter by its lower case with `shift` set; the keys here are the letters as typed. */
private fun KeyEvent.asKey(): Key = Key(if (shift && key.length == 1) key.uppercase() else key)

@Composable
private fun Line(line: Line) {
  // An empty Row draws no line at all, which would pull every line below it up by one.
  if (line.spans.all { it.text.isEmpty() }) return Text(" ")
  Row {
    for (span in line.spans) {
      val modifier = if (span.selected) Modifier.background(SELECTED) else Modifier
      val color = span.tone.color
      if (color == null) Text(span.text, modifier = modifier) else Text(span.text, modifier = modifier, color = color)
    }
  }
}

private val SELECTED = Color(60, 60, 90)

private val Tone.color: Color? get() = when (this) {
  Tone.Plain -> null
  Tone.Dim -> Color(130, 130, 130)
  Tone.Good -> Color(100, 220, 100)
  Tone.Warn -> Color(255, 180, 60)
  Tone.Bad -> Color(255, 90, 90)
}
