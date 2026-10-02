package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.feed
import io.github.kzagoris.proxenos.gui.res.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * The window-wide keys (GUI-SPEC §5), for the Window's `onPreviewKeyEvent`, so they work whatever
 * has focus. A chord acts on KeyDown only: one press arrives as KeyDown and KeyUp (measured). An
 * open dialog traps focus, so only Ctrl+W reaches past it; Esc is the dialog's own.
 */
fun shortcut(event: KeyEvent, state: GuiState, send: (GuiIntent) -> Unit, close: () -> Unit): Boolean {
  if (event.type != KeyEventType.KeyDown) return false
  if (event.isCtrlPressed && event.key == Key.W) return true.also { close() }
  if (state.dialogOpen) return false
  if (event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed) {
    val intent = when (event.key) {
      Key.One -> GuiIntent.Show(Destination.Workspaces)
      Key.Two -> GuiIntent.Show(Destination.Activity)
      Key.Three -> GuiIntent.Show(Destination.Connection)
      Key.N -> GuiIntent.AddWorkspace
      else -> return false
    }
    send(intent)
    return true
  }
  if (event.key == Key.Escape && ((state.destination == Destination.Connection && state.stage != null) ||
      (state.destination == Destination.Workspaces && state.workspace != null))) {
    send(GuiIntent.Back)
    return true
  }
  return false
}

@Composable
fun Shell(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser? = null) {
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(state.notice) {
    state.notice?.let {
      snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Indefinite)
      send(GuiIntent.DismissNotice)
    }
  }
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val compact = maxWidth < Look.compact
      if (compact) Column(Modifier.fillMaxSize()) {
        Header(state, send, compact = true)
        Body(state, send, compact = true, Modifier.weight(1f))
        BottomBar(state, send)
      } else Row(Modifier.fillMaxSize()) {
        Sidebar(state, send)
        Column(Modifier.fillMaxSize()) {
          Header(state, send, compact = false)
          Body(state, send, compact = false, Modifier.weight(1f))
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (compact) 64.dp else 0.dp))
    }
  }
  if (state.stopConfirmation) StopDialog(send)
  if (state.adding) AddDialog(state, send, chooser)
  if (state.commandConfirmation != null) CommandDialog(state, send)
  if (state.registration == Registration.Rename) RenameDialog(state, send)
  if (state.registration == Registration.Move) MoveDialog(state, send, chooser)
  if (state.registration == Registration.Reconfirm) ReconfirmDialog(state, send)
  if (state.registration == Registration.Forget) ForgetDialog(state, send)
  if (state.trying != null) TryDialog(state, send)
}

@Composable
private fun Body(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean, modifier: Modifier) {
  Column(modifier.fillMaxWidth()) {
    state.inFlight?.let { act ->
      Column(Modifier.padding(horizontal = Look.pad, vertical = 4.dp)) {
        Text(when (act) {
          ManagementAct.Stop -> "Stopping the Runtime"
          ManagementAct.Connect -> "Connecting the tunnel"
          ManagementAct.Disconnect -> "Disconnecting the tunnel"
          ManagementAct.AcknowledgeConnector -> "Recording your word about the connector"
          is ManagementAct.Register -> "Adding the Workspace"
          is ManagementAct.Rename -> "Renaming the Workspace"
          is ManagementAct.Reconfirm -> "Re-confirming the Workspace at Read"
          is ManagementAct.Forget -> "Forgetting the Workspace"
          is ManagementAct.SetLevel -> "Setting Access Level to ${act.level}"
          is ManagementAct.TryOperation<*> -> "Trying ${state.trying?.tool ?: "an Operation"}"
          else -> "Working"
        },
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    }
    if (state.destination != Destination.Connection) state.tunnelBanner()?.let { (text, tone) ->
      Box(Modifier.padding(horizontal = Look.pad, vertical = Look.gap)) {
        Banner(text, tone, if (tone == Tone.None) Res.drawable.link_off else Res.drawable.warning, "Review") {
          send(GuiIntent.ShowStage(Stage.Tunnel))
        }
      }
    }
    Box(Modifier.fillMaxSize()) {
      // Absent: nothing from before stays on screen (GUI-SPEC §4.1). Connection stays usable.
      if (state.snapshot == null && state.destination != Destination.Connection) AbsentPane(state, send)
      else when (state.destination) {
        Destination.Workspaces -> WorkspacesPane(state, send, compact)
        Destination.Activity -> ActivityPane(state)
        Destination.Connection -> ConnectionPane(state, send, compact)
      }
    }
  }
}

private fun Destination.icon(): DrawableResource = when (this) {
  Destination.Workspaces -> Res.drawable.folder
  Destination.Activity -> Res.drawable.history
  Destination.Connection -> Res.drawable.hub
}

@Composable
private fun Sidebar(state: GuiState, send: (GuiIntent) -> Unit) {
  val colours = MaterialTheme.colorScheme
  Column(
    Modifier.width(Look.sidebar).fillMaxHeight().background(colours.surfaceContainerLow)
      .hairlineEnd(colours.outlineVariant).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Box(Modifier.height(Look.row).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
      Text("Proxenos", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(6.dp))
    Btn("Add Workspace", { send(GuiIntent.AddWorkspace) }, Modifier.fillMaxWidth(), enabled = state.canAdd, primary = true,
      icon = Res.drawable.add)
    Spacer(Modifier.height(10.dp))
    Destination.entries.forEach { destination ->
      val selected = state.destination == destination
      Row(
        Modifier.fillMaxWidth().height(Look.row + 2.dp).clip(RoundedCornerShape(Look.corner))
          .background(if (selected) colours.secondaryContainer else Color.Transparent)
          .selectable(selected, role = Role.Tab) { send(GuiIntent.Show(destination)) }
          .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        val tint = if (selected) colours.onSurface else colours.onSurfaceVariant
        Ic(destination.icon(), tint)
        Text(destination.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tint,
          fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        state.badge(destination)?.let { DestinationBadge(it) }
      }
    }
  }
}

@Composable
private fun BottomBar(state: GuiState, send: (GuiIntent) -> Unit) {
  val colours = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth().height(56.dp).background(colours.surfaceContainerLow).hairlineTop(colours.outlineVariant)) {
    Destination.entries.forEach { destination ->
      val selected = state.destination == destination
      val tint = if (selected) colours.onSurface else colours.onSurfaceVariant
      Column(
        Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab) { send(GuiIntent.Show(destination)) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Ic(destination.icon(), tint)
          state.badge(destination)?.let { DestinationBadge(it) }
        }
        Text(destination.name, style = MaterialTheme.typography.labelSmall, color = tint,
          fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
      }
    }
  }
}

@Composable
private fun Header(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean) {
  val colours = MaterialTheme.colorScheme
  val detail = compact && ((state.destination == Destination.Connection && state.stage != null) ||
    (state.destination == Destination.Workspaces && state.workspace != null))
  BoxWithConstraints(Modifier.fillMaxWidth().hairlineBottom(colours.outlineVariant)) {
    val separateStrip = compact || maxWidth < 640.dp
    Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth().height(Look.header).padding(horizontal = Look.pad), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
        if (detail) Btn("Back", { send(GuiIntent.Back) }, icon = Res.drawable.arrow_back)
        Text(if (detail && state.destination == Destination.Connection) state.stage!!.name else state.destination.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (compact) Btn("Add Workspace", { send(GuiIntent.AddWorkspace) }, enabled = state.canAdd, icon = Res.drawable.add)
        else if (!separateStrip) StageStrip(state, send)
      }
      // Too narrow for all three stages at once: the strip scrolls rather than clip a stage's word.
      if (separateStrip) Box(Modifier.padding(start = Look.pad, end = Look.pad, bottom = Look.gap).horizontalScroll(rememberScrollState())) {
        StageStrip(state, send)
      }
    }
  }
}

/** Runtime · Tunnel · Connector, each measured on its own; a stage opens that stage on Connection. */
@Composable
private fun StageStrip(state: GuiState, send: (GuiIntent) -> Unit) {
  val colours = MaterialTheme.colorScheme
  Row(Modifier.height(28.dp).border(1.dp, colours.outlineVariant, RoundedCornerShape(Look.corner)),
    verticalAlignment = Alignment.CenterVertically) {
    Stage.entries.forEachIndexed { index, stage ->
      if (index > 0) VerticalDivider(color = colours.outlineVariant)
      Row(
        Modifier.fillMaxHeight().clip(RoundedCornerShape(Look.corner))
          .selectable(false, role = Role.Button) { send(GuiIntent.ShowStage(stage)) }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(stage.name, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
        StatusWord(state.reading(stage))
      }
    }
  }
}

/** The Runtime's absence, its reason, and the one way back: Start. */
@Composable
private fun AbsentPane(state: GuiState, send: (GuiIntent) -> Unit) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
    verticalArrangement = Arrangement.spacedBy(Look.gap * 1.5f)) {
    Ic(Res.drawable.power_settings_new, MaterialTheme.colorScheme.onSurfaceVariant, 40.dp)
    Text(
      when (state.attachment) {
        Attachment.Starting -> "Starting the Runtime"
        Attachment.Attaching -> "Attaching to the Runtime"
        else -> if (state.stopped) "The Runtime is Stopped" else "The Runtime is not running"
      },
      style = MaterialTheme.typography.headlineSmall,
    )
    RuntimeWords(state)
    Btn("Start Runtime", { send(GuiIntent.StartRuntime) }, enabled = state.canStart, primary = true, icon = Res.drawable.power_settings_new)
  }
}

@Composable
private fun ActivityPane(state: GuiState) {
  val snapshot = state.snapshot ?: return
  val running = snapshot.running.count { it.tool == RUN_COMMAND }
  val feed = snapshot.feed
  Column(Modifier.fillMaxSize().padding(Look.pad), verticalArrangement = Arrangement.spacedBy(Look.gap)) {
    Section("Running now · $running of 4")
    Section("This start · ${feed.size} entries · ${feed.count { it.needsAttention }} need attention")
  }
}

/** Enter submits from a field; KeyUp is consumed too, since foundation buttons activate on it. */
internal fun Modifier.enter(submit: () -> Unit) = onPreviewKeyEvent { event ->
  if (event.key == Key.Enter || event.key == Key.NumPadEnter) true.also { if (event.type == KeyEventType.KeyDown) submit() } else false
}

/** A dialog's own Esc: the test scene has no Esc-to-dismiss, and cancel is idempotent (GUI-SPEC §5). */
internal fun Modifier.escape(cancel: () -> Unit) = onPreviewKeyEvent { event ->
  if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) true.also { cancel() } else false
}

@Composable
private fun StopDialog(send: (GuiIntent) -> Unit) {
  val cancel = remember { FocusRequester() }
  val words = Wording.stopRuntime()
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelStop) },
    title = { Text(words.first()) },
    text = { Text(words.drop(1).joinToString(" ")) },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelStop) }, Modifier.focusRequester(cancel)) },
    confirmButton = { Btn("Stop Runtime", { send(GuiIntent.ConfirmStop) }) },
    modifier = Modifier.escape { send(GuiIntent.CancelStop) },
  )
  LaunchedEffect(Unit) { cancel.requestFocus() }
}
