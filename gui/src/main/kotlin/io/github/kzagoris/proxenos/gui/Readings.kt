package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.feed
import io.github.kzagoris.proxenos.gui.res.Res
import io.github.kzagoris.proxenos.gui.res.check_circle
import io.github.kzagoris.proxenos.gui.res.error
import io.github.kzagoris.proxenos.gui.res.help
import io.github.kzagoris.proxenos.gui.res.link_off
import io.github.kzagoris.proxenos.gui.res.power_settings_new
import io.github.kzagoris.proxenos.gui.res.sync
import io.github.kzagoris.proxenos.gui.res.warning
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.compose.resources.DrawableResource

/** What one stage reads: its word, the status colour it takes, and the icon that goes with both. */
data class Reading(val word: String, val tone: Tone, val icon: DrawableResource)

/**
 * Each stage from its own signal and none inferred from another (ADR 0009). The Runtime reads this
 * window's attachment — Attached is this window's link and never the tunnel's — and the stages below
 * read Can't tell, grey and never red, until there is a Runtime to measure them.
 */
fun GuiState.reading(stage: Stage): Reading {
  val snapshot = snapshot
  return when (stage) {
    Stage.Runtime -> when (attachment) {
      Attachment.Starting -> Reading("Starting", Tone.Info, Res.drawable.sync)
      Attachment.Attaching -> Reading("Attaching", Tone.Info, Res.drawable.sync)
      is Attachment.Attached -> Reading("Attached", Tone.Ok, Res.drawable.check_circle)
      is Attachment.Absent -> Reading(if (stopped) "Stopped" else "Not running", Tone.Warn, Res.drawable.power_settings_new)
    }
    Stage.Tunnel -> when (snapshot?.runtime?.state) {
      null -> cantTell
      RuntimeState.Connected -> Reading("Connected", Tone.Ok, Res.drawable.check_circle)
      RuntimeState.Connecting -> Reading("Connecting", Tone.Info, Res.drawable.sync)
      is RuntimeState.Failed -> Reading("Failed", Tone.Bad, Res.drawable.error)
      RuntimeState.Disconnected -> Reading("Disconnected", Tone.None, Res.drawable.link_off)
    }
    Stage.Connector -> when {
      snapshot == null -> cantTell
      snapshot.connectorUnconfirmed -> Reading("Unconfirmed", Tone.Warn, Res.drawable.warning)
      else -> Reading("Confirmed", Tone.Ok, Res.drawable.check_circle)
    }
  }
}

private val cantTell = Reading(Wording.CANT_TELL, Tone.None, Res.drawable.help)

/**
 * A destination's badge (GUI-SPEC §4.1), or null. Workspaces: `!` for a Broken Workspace or a
 * command still running in one now below Command. Activity: what needs attention, else what is
 * running. Connection: `!` for Failed or Unconfirmed.
 */
fun GuiState.badge(destination: Destination): String? {
  val snapshot = snapshot ?: return null
  val commands = snapshot.running.filter { it.tool == RUN_COMMAND }
  return when (destination) {
    Destination.Workspaces -> "!".takeIf {
      snapshot.workspaces.any { state ->
        state.broken || (state.workspace.accessLevel < AccessLevel.Command && commands.any { it.workspace == state.workspace.name })
      }
    }
    Destination.Activity -> snapshot.feed.count { it.needsAttention }.takeIf { it > 0 }?.toString()
      ?: commands.size.takeIf { it > 0 }?.toString()
    Destination.Connection -> "!".takeIf { snapshot.runtime.state is RuntimeState.Failed || snapshot.connectorUnconfirmed }
  }
}

/**
 * The one-line tunnel banner on the other destinations: Failed, Connecting with words to quote,
 * or Disconnected. Unconfirmed has none — it is the Connector stage's own state. Null otherwise.
 */
fun GuiState.tunnelBanner(): Pair<String, Tone>? {
  val snapshot = snapshot ?: return null
  return when (snapshot.runtime.state) {
    is RuntimeState.Failed -> "The tunnel Failed: a link that worked has gone stale, and the tunnel client keeps retrying." to Tone.Bad
    // Since this start or since the user last Connected: whichever it was entered at.
    RuntimeState.Connecting -> snapshot.connectingWords?.let {
      "The tunnel is still Connecting: no poll has succeeded since ${clock.format(snapshot.runtime.enteredAt)}." to Tone.Warn
    }
    RuntimeState.Disconnected -> "The tunnel is Disconnected: you took the link down. The Runtime and every Access Level are unchanged." to Tone.None
    RuntimeState.Connected -> null
  }
}

/** Wall-clock time in the desktop's zone, as the stage details show it. */
val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
