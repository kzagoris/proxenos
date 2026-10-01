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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.absoluteRoot
import io.github.kzagoris.proxenos.frontend.feed
import io.github.kzagoris.proxenos.frontend.overlaps
import io.github.kzagoris.proxenos.gui.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource

/** How this window names itself in the domain's wording ("… this window holds the Runtime's stream"). */
private const val SELF = "this window"

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
  if (event.key == Key.Escape && state.destination == Destination.Connection && state.stage != null) {
    send(GuiIntent.Back)
    return true
  }
  return false
}

@Composable
fun Shell(state: GuiState, send: (GuiIntent) -> Unit) {
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
  if (state.adding) AddDialog(state, send)
}

@Composable
private fun Body(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean, modifier: Modifier) {
  Column(modifier.fillMaxWidth()) {
    state.inFlight?.let { act ->
      Column(Modifier.padding(horizontal = Look.pad, vertical = 4.dp)) {
        Text(if (act == ManagementAct.Stop) "Stopping the Runtime" else "Adding the Workspace",
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
        Destination.Workspaces -> WorkspacesPane(state)
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

private fun Stage.icon(): DrawableResource = when (this) {
  Stage.Runtime -> Res.drawable.power_settings_new
  Stage.Tunnel -> Res.drawable.link
  Stage.Connector -> Res.drawable.extension
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
  val detail = compact && state.destination == Destination.Connection && state.stage != null
  Column(Modifier.fillMaxWidth().hairlineBottom(colours.outlineVariant)) {
    Row(Modifier.fillMaxWidth().height(Look.header).padding(horizontal = Look.pad), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
      if (detail) Btn("Back", { send(GuiIntent.Back) }, icon = Res.drawable.arrow_back)
      Text(if (detail) state.stage!!.name else state.destination.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      if (compact) Btn("Add Workspace", { send(GuiIntent.AddWorkspace) }, enabled = state.canAdd, icon = Res.drawable.add)
      else StageStrip(state, send)
    }
    // Too narrow for all three stages at once: the strip scrolls rather than clip a stage's word.
    if (compact) Box(Modifier.padding(start = Look.pad, end = Look.pad, bottom = Look.gap).horizontalScroll(rememberScrollState())) {
      StageStrip(state, send)
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

/** What the Runtime stage says beyond its word: this window's link, or why there is none. */
@Composable
private fun RuntimeWords(state: GuiState) {
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  when (val attachment = state.attachment) {
    Attachment.Starting -> Text(Wording.starting(SELF), color = muted)
    Attachment.Attaching -> Text(Wording.attaching(SELF), color = muted)
    is Attachment.Attached -> Text(Wording.attached(SELF), color = muted)
    is Attachment.Absent -> {
      (attachment.reason as? Reason.StartFailed)?.let { Text(it.words) }
      Text("This window holds no stream from a Runtime, so it shows nothing of what is exposed.", color = muted)
      Text(Wording.NO_AUTOSTART, color = muted)
    }
  }
}

@Composable
private fun ConnectionPane(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean) {
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
    Column(modifier.then(keys).padding(Look.pad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Stage.entries.forEach { stage ->
        val selected = !compact && (state.stage ?: Stage.Runtime) == stage
        Row(
          Modifier.fillMaxWidth().height(Look.row + 4.dp).clip(RoundedCornerShape(Look.corner))
            .background(if (selected) colours.secondaryContainer else Color.Transparent)
            .focusRequester(rows.getValue(stage))
            .onFocusChanged { if (it.isFocused) focused = stage else if (focused == stage) focused = null }
            .selectable(selected) { send(GuiIntent.ShowStage(stage)) }.padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Ic(stage.icon(), colours.onSurfaceVariant)
          Text(stage.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
          StatusWord(state.reading(stage))
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
            Wording.connecting(clock.format(snapshot.runtime.enteredAt), words).forEach { Text(it) }
          } ?: Text("No poll has succeeded yet since ${clock.format(snapshot.runtime.enteredAt)}.", color = muted)
          RuntimeState.Disconnected -> Text(
            "You took the link down. Disconnect is not Stop and not Access Level None: the Runtime keeps running " +
              "and every Access Level is unchanged.",
          )
        }
        // Whatever the link reads, restoring it would not show whether ChatGPT still has a connector.
        if (snapshot != null) Text(Wording.CONNECTED_CAVEAT, color = muted)
      }
      Stage.Connector -> when {
        snapshot == null -> Text(Wording.CANT_TELL_CONNECTOR, color = muted)
        snapshot.connectorUnconfirmed -> Wording.unconfirmed("I created the connector again").take(2).forEach { Text(it) }
        else -> Text(Wording.CONNECTOR_CONFIRMED)
      }
    }
  }
}

private fun AccessLevel.reading(): Reading = when (this) {
  AccessLevel.None -> Reading("None", Tone.None, Res.drawable.visibility_off)
  AccessLevel.Read -> Reading("Read", Tone.Info, Res.drawable.visibility)
  AccessLevel.Write -> Reading("Write", Tone.Warn, Res.drawable.edit)
  AccessLevel.Command -> Reading("Command", Tone.Bad, Res.drawable.terminal)
}

@Composable
private fun WorkspacesPane(state: GuiState) {
  val workspaces = state.snapshot?.workspaces.orEmpty()
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Look.pad)) {
    if (workspaces.isEmpty()) {
      Text("No Workspaces yet.", style = MaterialTheme.typography.titleMedium)
      Text("Add Workspace (Ctrl+N) registers a folder at Read.", color = muted)
    }
    workspaces.forEach { WorkspaceRow(it) }
  }
}

@Composable
private fun WorkspaceRow(state: WorkspaceState) {
  val colours = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth().hairlineBottom(colours.outlineVariant).padding(vertical = Look.gap),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
    Column(Modifier.weight(1f)) {
      Text(state.workspace.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(state.workspace.root, style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current),
        color = colours.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (state.broken) StatusWord(Reading("Broken", Tone.Bad, Res.drawable.error))
    }
    StatusWord(state.workspace.accessLevel.reading())
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

/** A dialog's own Esc: the test scene has no Esc-to-dismiss, and cancel is idempotent (GUI-SPEC §5). */
private fun Modifier.escape(cancel: () -> Unit) = onPreviewKeyEvent { event ->
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

/** The overlap [words] for [root] as typed, against the [workspaces] it was checked with. */
private data class OverlapCheck(val root: String, val workspaces: List<WorkspaceState>, val words: List<String>)

/** Register: a Root typed in full, a name defaulting to the folder's, and every overlap named. */
@Composable
private fun AddDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  var root by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  val field = remember { FocusRequester() }
  val workspaces = state.snapshot?.workspaces.orEmpty()
  // The overlap words and the Root and Workspaces they were checked against. Overlap resolves real
  // paths, which is file I/O, so it runs off the event thread — and nothing is sent until the check
  // has caught up with what is typed, or a Register could go out under a stale "Add at Read".
  var checked by remember { mutableStateOf(OverlapCheck("", workspaces, emptyList())) }
  LaunchedEffect(root, workspaces) {
    val words = if (root.isBlank()) emptyList() else withContext(Dispatchers.IO) {
      val absolute = absoluteRoot(root)
      overlaps(absolute, workspaces).takeIf { it.isNotEmpty() }?.let { Wording.overlap(absolute.toString(), it) }.orEmpty()
    }
    checked = OverlapCheck(root, workspaces, words)
  }
  val overlapping = checked.words
  val ready = root.isNotBlank() && state.inFlight == null && checked.root == root && checked.workspaces == workspaces
  val folder = root.trim().trimEnd('/').substringAfterLast('/')
  val label = if (overlapping.isEmpty()) "Add at Read" else "Add anyway, at Read"
  val submit = { if (ready) send(GuiIntent.Register(root, name.ifBlank { null })) }
  val enter = Modifier.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) true.also { submit() } else false
  }
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelAdd) },
    title = { Text("Add Workspace") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth().focusRequester(field).then(enter),
          label = { Text("Root") }, singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMono.current))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().then(enter),
          label = { Text("Name") }, placeholder = { Text(folder) }, singleLine = true)
        overlapping.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn) }
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
        if (state.inFlight != null) LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelAdd) }) },
    confirmButton = { Btn(label, submit, enabled = ready, primary = true) },
    modifier = Modifier.escape { send(GuiIntent.CancelAdd) },
  )
  LaunchedEffect(Unit) { field.requestFocus() }
}
