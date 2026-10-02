package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.RunningOperation
import io.github.kzagoris.proxenos.coreapi.WorkspaceId
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.gui.res.*

private fun AccessLevel.reading(): Reading = when (this) {
  AccessLevel.None -> Reading("None", Tone.None, Res.drawable.visibility_off)
  AccessLevel.Read -> Reading("Read", Tone.Info, Res.drawable.visibility)
  AccessLevel.Write -> Reading("Write", Tone.Warn, Res.drawable.edit)
  AccessLevel.Command -> Reading("Command", Tone.Bad, Res.drawable.terminal)
}

private fun GuiState.loweredCommands(workspace: WorkspaceState): List<RunningOperation> =
  if (workspace.workspace.accessLevel == AccessLevel.Command) emptyList()
  else snapshot?.running.orEmpty().filter { it.tool == RUN_COMMAND && it.workspace == workspace.workspace.name }

@Composable
internal fun WorkspacesPane(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean) {
  if (compact) {
    if (state.workspace == null) WorkspaceList(state, send, compact, Modifier.fillMaxSize())
    else WorkspaceDetail(state, send, Modifier.fillMaxSize())
  } else Row(Modifier.fillMaxSize()) {
    WorkspaceList(state, send, compact, Modifier.fillMaxWidth(0.36f).fillMaxHeight().hairlineEnd(MaterialTheme.colorScheme.outlineVariant))
    WorkspaceDetail(state, send, Modifier.weight(1f).fillMaxHeight())
  }
}

@Composable
private fun WorkspaceList(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean, modifier: Modifier) {
  val workspaces = state.snapshot?.workspaces.orEmpty()
  val ids = workspaces.map { it.workspace.id }
  val rows = remember(ids) { ids.associateWith { FocusRequester() } }
  var focused by remember { mutableStateOf<WorkspaceId?>(null) }
  val keys = Modifier.listKeys(ids, { focused }) { to ->
    rows.getValue(to).requestFocus()
    // In one pane, activation opens the detail; moving focus keeps the list available.
    if (!compact) send(GuiIntent.SelectWorkspace(to))
  }
  Column(modifier.verticalScroll(rememberScrollState()).then(keys).padding(Look.pad),
    verticalArrangement = Arrangement.spacedBy(2.dp)) {
    if (workspaces.isEmpty()) {
      Text("No Workspaces yet.", style = MaterialTheme.typography.titleMedium)
      Text("Add Workspace (Ctrl+N) registers a folder at Read.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    workspaces.forEach { workspace ->
      key(workspace.workspace.id) {
        val id = workspace.workspace.id
        WorkspaceRow(workspace, state.workspace == id, state.loweredCommands(workspace).isNotEmpty(),
          Modifier.focusRequester(rows.getValue(id)).onFocusChanged {
            if (it.isFocused) focused = id else if (focused == id) focused = null
          }) { send(GuiIntent.SelectWorkspace(id)) }
      }
    }
  }
}

@Composable
private fun WorkspaceRow(state: WorkspaceState, selected: Boolean, lowered: Boolean, modifier: Modifier, select: () -> Unit) {
  val colours = MaterialTheme.colorScheme
  Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Look.corner))
    .background(if (selected) colours.secondaryContainer else Color.Transparent)
    .selectable(selected, role = Role.Button, onClick = select).padding(horizontal = 6.dp, vertical = Look.gap),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
        Text(state.workspace.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        StatusWord(state.workspace.accessLevel.reading())
      }
      Text(state.workspace.root, style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current),
        color = colours.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (state.broken) StatusWord(Reading("Broken", Tone.Bad, Res.drawable.error))
      if (lowered) StatusWord(Reading("Command still running", Tone.Warn, Res.drawable.warning))
    }
  }
}

/** GUI-SPEC §4.3. Which tab shows is the window's own business, not the Runtime's. */
private enum class DetailTab { Access, Tools, Registration }

@Composable
private fun WorkspaceDetail(state: GuiState, send: (GuiIntent) -> Unit, modifier: Modifier) {
  val selected = state.selectedWorkspace
  var tab by remember { mutableStateOf(DetailTab.Access) }
  Column(modifier.verticalScroll(rememberScrollState()).padding(Look.pad),
    verticalArrangement = Arrangement.spacedBy(Look.gap * 1.5f)) {
    if (selected == null) {
      Text(if (state.workspace == null) "Select a Workspace." else "This Workspace is gone.",
        style = MaterialTheme.typography.titleMedium)
      return@Column
    }
    val workspace = selected.workspace
    Text(workspace.name, style = MaterialTheme.typography.headlineSmall)
    Text(workspace.root, style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current),
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    StatusWord(workspace.accessLevel.reading())
    if (selected.broken) {
      Banner("Broken", Tone.Bad, Res.drawable.error)
      Text("The Root no longer resolves to the registered directory. ChatGPT cannot use this Workspace until you re-confirm it.",
        style = MaterialTheme.typography.bodySmall)
      Btn("Re-confirm this folder…", { send(GuiIntent.AskReconfirm) }, enabled = state.canActOnWorkspace)
      Btn("Choose another folder…", { send(GuiIntent.AskMove) }, enabled = state.canActOnWorkspace)
    }
    state.loweredCommands(selected).forEach { running ->
      Column(verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        Banner("Command still running below Command", Tone.Warn, Res.drawable.warning)
        Text(Wording.lowered(state.commandOf(running), workspace.name, workspace.accessLevel,
          clock.format(running.startedAt), running.promoted), style = MaterialTheme.typography.bodySmall)
        Btn("Show running command", { send(GuiIntent.ShowActivity(running.entry)) })
      }
    }
    PrimaryTabRow(tab.ordinal, containerColor = Color.Transparent) {
      DetailTab.entries.forEach { Tab(tab == it, { tab = it }, text = { Text(it.name) }) }
    }
    when (tab) {
      DetailTab.Access -> {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
          SingleChoiceSegmentedButtonRow {
            AccessLevel.entries.forEachIndexed { index, level ->
              SegmentedButton(
                selected = workspace.accessLevel == level,
                onClick = { send(GuiIntent.SetLevel(level)) },
                enabled = state.canActOnWorkspace,
                shape = SegmentedButtonDefaults.itemShape(index, AccessLevel.entries.size),
                icon = { Ic(level.reading().icon, LocalStatus.current.of(level.reading().tone)) },
              ) { Text(level.name) }
            }
          }
        }
        Text(Wording.NONE_EXPLANATION, style = MaterialTheme.typography.bodySmall)
        Wording.commandAuthority(workspace).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
      DetailTab.Tools -> ToolsTab(state, selected, send)
      DetailTab.Registration -> {
        Btn("Rename…", { send(GuiIntent.AskRename) }, enabled = state.canActOnWorkspace)
        Btn("Move to another folder…", { send(GuiIntent.AskMove) }, enabled = state.canActOnWorkspace)
        Btn("Forget…", { send(GuiIntent.AskForget) }, enabled = state.canActOnWorkspace)
      }
    }
  }
}

@Composable
internal fun CommandDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  val workspace = state.snapshot?.workspaces?.find { it.workspace.id == state.commandConfirmation }?.workspace ?: return
  val cancel = remember { FocusRequester() }
  val words = Wording.raiseToCommand(workspace)
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelCommand) },
    title = { Text(words.first()) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        words.drop(1).forEach { Text(it) }
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
      }
    },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelCommand) }, Modifier.focusRequester(cancel)) },
    confirmButton = { Btn("Raise to Command", { send(GuiIntent.ConfirmCommand) }, enabled = state.inFlight == null) },
    modifier = Modifier.confirmation { send(GuiIntent.CancelCommand) },
  )
  LaunchedEffect(Unit) { cancel.requestFocus() }
}
