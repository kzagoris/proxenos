package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.gui.res.*
import org.jetbrains.compose.resources.DrawableResource

private const val SELF = "this window"

private fun Stage.icon(): DrawableResource = when (this) {
  Stage.Runtime -> Res.drawable.power_settings_new
  Stage.Tunnel -> Res.drawable.link
  Stage.Connector -> Res.drawable.extension
}

/** What the Runtime stage says beyond its word: this window's link, or why there is none. */
@Composable
internal fun RuntimeWords(state: GuiState) {
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  when (val attachment = state.attachment) {
    Attachment.Starting -> Text(Wording.starting(SELF), color = muted)
    Attachment.Attaching -> Text(Wording.attaching(SELF), color = muted)
    is Attachment.Attached -> Text(Wording.attached(SELF), color = muted)
    is Attachment.Absent -> {
      (attachment.reason as? Reason.StartFailed)?.let { Text(it.words) }
      Text("This window holds no stream from a Runtime, so it shows nothing of what is exposed.", color = muted)
    }
  }
  Text(Wording.NO_AUTOSTART, color = muted)
}

@Composable
internal fun ConnectionPane(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean) {
  val colours = MaterialTheme.colorScheme
  val rows = remember { Stage.entries.associateWith { FocusRequester() } }
  var focused by remember { mutableStateOf<Stage?>(null) }
  // GUI-SPEC §5: ↑/↓/Home/End move and selection follows. In the compact list a selection would
  // replace the list with its detail, so there they move focus alone.
  val keys = Modifier.onPreviewKeyEvent { event ->
    val from = focused ?: return@onPreviewKeyEvent false
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val stages = Stage.entries
    val to = when (event.key) {
      Key.DirectionUp -> stages.getOrNull(from.ordinal - 1)
      Key.DirectionDown -> stages.getOrNull(from.ordinal + 1)
      Key.MoveHome -> stages.first()
      Key.MoveEnd -> stages.last()
      else -> return@onPreviewKeyEvent false
    } ?: return@onPreviewKeyEvent true
    rows.getValue(to).requestFocus()
    if (!compact) send(GuiIntent.ShowStage(to))
    true
  }
  val list = @Composable { modifier: Modifier ->
    BoxWithConstraints(modifier) {
      val narrow = maxWidth < 220.dp
      Column(Modifier.fillMaxWidth().then(keys).padding(Look.pad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Stage.entries.forEach { stage ->
          val selected = !compact && (state.stage ?: Stage.Runtime) == stage
          Row(
            Modifier.fillMaxWidth().height(if (narrow) 56.dp else Look.row + 4.dp).clip(RoundedCornerShape(Look.corner))
              .background(if (selected) colours.secondaryContainer else Color.Transparent)
              .focusRequester(rows.getValue(stage))
              .onFocusChanged { if (it.isFocused) focused = stage else if (focused == stage) focused = null }
              .selectable(selected) { send(GuiIntent.ShowStage(stage)) }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (narrow) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ic(stage.icon(), colours.onSurfaceVariant)
                Text(stage.name, style = MaterialTheme.typography.bodyMedium)
              }
              StatusWord(state.reading(stage))
            } else {
              Ic(stage.icon(), colours.onSurfaceVariant)
              Text(stage.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
              StatusWord(state.reading(stage))
            }
          }
        }
      }
    }
  }
  when {
    compact && state.stage == null -> list(Modifier.fillMaxWidth())
    compact -> StageDetail(state, state.stage!!, send, Modifier.fillMaxSize())
    // Just above the compact breakpoint the sidebar takes 228 dp: the list gives way to the detail.
    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
      val listWidth = minOf(320.dp, maxWidth * 0.4f)
      Row(Modifier.fillMaxSize()) {
        list(Modifier.width(listWidth).fillMaxHeight().hairlineEnd(colours.outlineVariant))
        StageDetail(state, state.stage ?: Stage.Runtime, send, Modifier.weight(1f).fillMaxHeight())
      }
    }
  }
}

@Composable
private fun StageDetail(state: GuiState, stage: Stage, send: (GuiIntent) -> Unit, modifier: Modifier) {
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  Column(modifier.verticalScroll(rememberScrollState()).padding(Look.pad), verticalArrangement = Arrangement.spacedBy(Look.gap * 1.5f)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stage.name, style = MaterialTheme.typography.headlineSmall)
      StatusWord(state.reading(stage))
    }
    Section("Reading")
    val snapshot = state.snapshot
    when (stage) {
      Stage.Runtime -> {
        RuntimeWords(state)
        if (snapshot != null) Btn("Stop Runtime…", { send(GuiIntent.AskStop) }, enabled = state.canStop)
        else Btn("Start Runtime", { send(GuiIntent.StartRuntime) }, enabled = state.canStart, primary = true,
          icon = Res.drawable.power_settings_new)
        Text("Closing this window leaves the Runtime running: it never stops, disconnects or withholds anything.",
          style = MaterialTheme.typography.bodySmall, color = muted)
      }
      Stage.Tunnel -> {
        when (val tunnel = snapshot?.runtime?.state) {
          null -> Text(Wording.CANT_TELL_TUNNEL, color = muted)
          RuntimeState.Connected -> Text("The last successful poll is recent: the link is up.")
          is RuntimeState.Failed -> Text(Wording.complaint(tunnel.complaint))
          RuntimeState.Connecting -> snapshot.connectingWords?.let { words ->
            Section("First-run credentials")
            Wording.connecting(clock.format(snapshot.runtime.enteredAt), words).forEach { Text(it) }
            Text(Wording.SETUP_WIZARD)
          } ?: Text("No poll has succeeded yet since ${clock.format(snapshot.runtime.enteredAt)}.", color = muted)
          RuntimeState.Disconnected -> Text(
            "You took the link down. Disconnect is not Stop and not Access Level None: the Runtime keeps running " +
              "and every Access Level is unchanged.",
          )
        }
        if (snapshot != null) {
          Text(Wording.DISCONNECT)
          if (snapshot.runtime.state == RuntimeState.Disconnected)
            Btn("Connect", { send(GuiIntent.ConnectTunnel) }, enabled = state.canConnect, icon = Res.drawable.link)
          else Btn("Disconnect", { send(GuiIntent.DisconnectTunnel) }, enabled = state.canDisconnect, icon = Res.drawable.link_off)
        }
        // Whatever the link reads, restoring it would not show whether ChatGPT still has a connector.
        Text(Wording.CONNECTED_CAVEAT, color = muted)
      }
      Stage.Connector -> when {
        snapshot == null -> Text(Wording.CANT_TELL_CONNECTOR, color = muted)
        snapshot.connectorUnconfirmed -> {
          Wording.unconfirmed("I created the connector again").forEach { Text(it) }
          Btn("I created the connector again", { send(GuiIntent.AcknowledgeConnector) }, enabled = state.canAcknowledgeConnector)
        }
        else -> Text(Wording.CONNECTOR_CONFIRMED)
      }
    }
  }
}
