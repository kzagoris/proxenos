package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.ActivityOutcome
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.coreapi.RunningCommand
import io.github.kzagoris.proxenos.coreapi.RunningOperation
import io.github.kzagoris.proxenos.frontend.FeedRow
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.feed
import io.github.kzagoris.proxenos.frontend.lastLine
import io.github.kzagoris.proxenos.frontend.runningFor
import io.github.kzagoris.proxenos.frontend.took
import io.github.kzagoris.proxenos.gui.res.*
import java.time.Instant
import kotlinx.coroutines.launch

/** An outcome's pill: its word, the status colour it takes (GUI-SPEC §6) and the icon that goes with both. */
private fun ActivityOutcome.reading(): Reading {
  val (tone, icon) = when (this) {
    ActivityOutcome.InFlight -> Tone.Info to Res.drawable.sync
    is ActivityOutcome.Ok -> Tone.Ok to Res.drawable.check_circle
    is ActivityOutcome.Failed -> Tone.Bad to Res.drawable.error
    else -> Tone.Warn to Res.drawable.warning
  }
  return Reading(said, tone, icon)
}

/** What an unresolved outcome means, where its entry is selected. Lost never is: it belongs to an earlier start. */
private fun ActivityOutcome.explanation(): String? = when (this) {
  is ActivityOutcome.Uncertain -> Wording.UNCERTAIN
  is ActivityOutcome.Undelivered -> Wording.UNDELIVERED
  is ActivityOutcome.Unclaimed -> Wording.UNCLAIMED
  else -> null
}

/** As last read — reads come once a second, so this ticks without a timer of its own — else since it started. */
private fun GuiState.elapsedOf(running: RunningOperation): String =
  runningFor(outputs[running.entry]?.elapsed?.inWholeSeconds ?: java.time.Duration.between(running.startedAt, Instant.now()).seconds)

@Composable
internal fun ActivityPane(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean) {
  if (compact) {
    if (state.activity == null) ActivityList(state, send, compact, Modifier.fillMaxSize())
    else ActivityDetail(state, send, Modifier.fillMaxSize())
  } else Row(Modifier.fillMaxSize()) {
    ActivityList(state, send, compact, Modifier.fillMaxWidth(0.5f).fillMaxHeight().hairlineEnd(MaterialTheme.colorScheme.outlineVariant))
    ActivityDetail(state, send, Modifier.weight(1f).fillMaxHeight())
  }
}

@Composable
private fun ActivityList(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean, modifier: Modifier) {
  val snapshot = state.snapshot ?: return
  val feed = remember(snapshot.activity, snapshot.start) { snapshot.feed }
  val attention = remember(feed) { feed.count { it.needsAttention } }
  val rows = state.feedRows
  Column(modifier.padding(Look.pad), verticalArrangement = Arrangement.spacedBy(Look.gap)) {
    Section("Running now · ${state.running.size} of ${Operation.COMMAND_CONCURRENCY_CAP}")
    if (state.running.isEmpty()) Text("Nothing is running.", style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    else RunningRows(state, send, compact)
    Spacer(Modifier.height(Look.gap))
    Section("This start · since ${snapshot.start?.let { clock.format(it.at) } ?: "—"} · ${feed.size} entries · " +
      "$attention need attention")
    if (rows.isEmpty()) Text("Nothing recorded since the Runtime started.", style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Feed(rows, state.activity, compact, send, Modifier.weight(1f))
  }
}

@Composable
private fun RunningRows(state: GuiState, send: (GuiIntent) -> Unit, compact: Boolean) {
  val ids = state.running.map { it.entry }
  val rows = remember(ids) { ids.associateWith { FocusRequester() } }
  var focused by remember { mutableStateOf<ActivityEntryId?>(null) }
  // In one pane, activation opens the detail; elsewhere selection follows focus.
  Column(Modifier.listKeys(ids, { focused }) { id ->
    rows.getValue(id).requestFocus()
    if (!compact) send(GuiIntent.ShowActivity(id))
  }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    state.running.forEach { running ->
      key(running.entry) {
        RunningRow(state, running, state.activity == running.entry,
          Modifier.focusRequester(rows.getValue(running.entry)).onFocusChanged {
            if (it.isFocused) focused = running.entry else if (focused == running.entry) focused = null
          }) { send(GuiIntent.ShowActivity(running.entry)) }
      }
    }
  }
}

/** Command, Workspace, elapsed, Promoted, and the last line it printed. */
@Composable
private fun RunningRow(state: GuiState, running: RunningOperation, selected: Boolean, modifier: Modifier, select: () -> Unit) {
  val colours = MaterialTheme.colorScheme
  val mono = LocalMono.current
  Column(modifier.fillMaxWidth().selectableRow(selected, select).padding(horizontal = 6.dp, vertical = Look.gap)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
      Text(state.commandOf(running), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = mono),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
      StatusWord(if (running.stopping != null) Reading("Stopping", Tone.Bad, Res.drawable.stop_circle)
        else Reading("running ${state.elapsedOf(running)}", Tone.Info, Res.drawable.sync))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
      Text(running.workspace ?: "—", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (running.promoted) StatusWord(Reading("Promoted", Tone.Info, Res.drawable.history))
    }
    state.outputs[running.entry]?.let { output ->
      Text(output.lastLine ?: "(nothing printed yet)",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), color = colours.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * This start's rows, newest first. Keyed by each row's first entry, which a growing `get_result`
 * fold keeps, so the reading position survives newer rows arriving above it (GUI-SPEC §3.4); the
 * rows above it are offered as "n newer ↑". At the newest row, the newest row stays in view.
 */
@Composable
private fun Feed(rows: List<FeedRow>, selected: ActivityEntryId?, compact: Boolean, send: (GuiIntent) -> Unit, modifier: Modifier) {
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val keys = remember(rows) { rows.map { it.key } }
  val top = keys.first()
  val newest = remember { object { var key: ActivityEntryId? = null } }
  // Before the next measure, so the position read is the one the reader was at before rows arrived.
  SideEffect {
    if (newest.key != null && top != newest.key && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) {
      list.requestScrollToItem(0)
    }
    newest.key = top
  }
  val newer by remember { derivedStateOf { list.firstVisibleItemIndex } }
  val requesters = remember { mutableMapOf<ActivityEntryId, FocusRequester>() }
  var focused by remember { mutableStateOf<ActivityEntryId?>(null) }
  Box(modifier.fillMaxWidth()) {
    LazyColumn(Modifier.fillMaxSize().listKeys(keys, { focused }) { key ->
      val index = keys.indexOf(key)
      scope.launch {
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        // A row scrolled into view registers its requester once it has been composed.
        if (key !in requesters) withFrameNanos {}
        requesters[key]?.requestFocus()
      }
      if (!compact) send(GuiIntent.ShowActivity(rows[index].id))
    }, state = list) {
      items(rows, key = { it.key.value }) { row ->
        val id = row.key
        val requester = remember { FocusRequester() }
        DisposableEffect(id) {
          requesters[id] = requester
          onDispose { requesters.remove(id) }
        }
        FeedRowItem(row, selected != null && selected in row,
          Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) focused = id else if (focused == id) focused = null
          }) { send(GuiIntent.ShowActivity(row.id)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
    if (newer > 0) Box(Modifier.align(Alignment.TopCenter).padding(top = Look.gap)) {
      Btn("$newer newer ↑", { scope.launch { list.scrollToItem(0) } }, Modifier.background(MaterialTheme.colorScheme.surface,
        RoundedCornerShape(Look.corner)))
    }
  }
}

/** Time, Origin, Workspace, tool and its arguments, the outcome, and `!` for what needs attention. */
@Composable
private fun FeedRowItem(row: FeedRow, selected: Boolean, modifier: Modifier, select: () -> Unit) {
  val colours = MaterialTheme.colorScheme
  val status = LocalStatus.current
  val mono = LocalMono.current
  val entry = row.entries.last()
  val attention = row.entries.any { it.needsAttention }
  Row(modifier.fillMaxWidth().height(Look.row).selectableRow(selected, select).padding(horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
    Text(if (attention) "!" else "", Modifier.width(8.dp), color = status.bad, fontWeight = FontWeight.Bold,
      style = MaterialTheme.typography.bodyMedium)
    Text(clock.format(entry.at), style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), color = colours.onSurfaceVariant)
    Ic(entry.origin.icon(), colours.onSurfaceVariant)
    Text(entry.workspace ?: "—", Modifier.widthIn(max = 110.dp), style = MaterialTheme.typography.bodySmall,
      maxLines = 1, overflow = TextOverflow.Ellipsis)
    // A fold is counted, never hidden: every poll is still in the account.
    Text(buildAnnotatedString {
      append(entry.tool)
      if (row.entries.size > 1) append(" ×${row.entries.size}")
      withStyle(SpanStyle(color = colours.onSurfaceVariant, fontFamily = mono)) { append("  "); append(entry.arguments.replace('\n', ' ')) }
    }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Pill(entry.outcome.reading())
  }
}

private fun Origin.icon() = when (this) {
  Origin.ChatGpt -> Res.drawable.smart_toy
  Origin.Frontend -> Res.drawable.person
}

private fun Origin.words() = when (this) {
  Origin.ChatGpt -> "ChatGPT"
  Origin.Frontend -> "a frontend (tried by hand)"
}

@Composable
private fun Pill(reading: Reading) {
  Box(Modifier.clip(RoundedCornerShape(50)).background(LocalStatus.current.of(reading.tone).copy(alpha = 0.14f))
    .padding(horizontal = 8.dp, vertical = 2.dp)) {
    StatusWord(reading)
  }
}

@Composable
private fun ActivityDetail(state: GuiState, send: (GuiIntent) -> Unit, modifier: Modifier) {
  Column(modifier.verticalScroll(rememberScrollState()).padding(Look.pad), verticalArrangement = Arrangement.spacedBy(Look.gap * 1.5f)) {
    val running = state.selectedRunning
    val entry = state.selectedEntry
    when {
      state.activity == null -> Text("Select a running command or an entry.", style = MaterialTheme.typography.titleMedium)
      running != null -> RunningDetail(state, running, send)
      entry != null -> EntryDetail(state, entry, send)
      // Selection is by identity: nothing else is selected in its place (GUI-SPEC §3.4).
      else -> Text("This entry is gone.", style = MaterialTheme.typography.titleMedium)
    }
  }
}

@Composable
private fun RunningDetail(state: GuiState, running: RunningOperation, send: (GuiIntent) -> Unit) {
  val command = state.commandOf(running)
  Text(command, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = LocalMono.current))
  Text("In '${running.workspace ?: "—"}' · started ${clock.format(running.startedAt)} · running ${state.elapsedOf(running)}",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  if (running.promoted) StatusWord(Reading("Promoted, Handle ${running.entry.value}", Tone.Info, Res.drawable.history))
  running.stopping?.let { Banner(Wording.stopping(it), Tone.Bad, Res.drawable.stop_circle, maxLines = 4) }
  Btn("Stop command…", { send(GuiIntent.AskStopCommand) }, enabled = state.canStopCommand, icon = Res.drawable.stop_circle)
  state.snapshot?.workspaces?.find { it.workspace.name == running.workspace }?.workspace
    ?.takeIf { it.accessLevel != AccessLevel.Command }?.let { workspace ->
      Text(Wording.lowered(command, workspace.name, workspace.accessLevel, clock.format(running.startedAt), running.promoted),
        style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn)
    }
  Section("What get_result would return now")
  val output = state.outputs[running.entry]
  if (output == null) Text("Reading what it has printed…", style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)
  else Output(output)
}

/** The one buffer, following its tail only while it is scrolled to the end. */
@Composable
private fun Output(output: RunningCommand) {
  val text = output.outputSoFar
  val dropped = if (output.droppedBytes > 0) " · ${Wording.dropped(output.droppedBytes)}" else ""
  val bytes = remember(text) { text.toByteArray().size }
  Text("$bytes bytes$dropped", style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)
  val scroll = rememberScrollState()
  // Read as it stood before this text was laid out; never measured yet counts as at the end.
  val atEnd = Snapshot.withoutReadObservation { scroll.maxValue == Int.MAX_VALUE || scroll.value >= scroll.maxValue }
  LaunchedEffect(text) {
    if (atEnd) {
      withFrameNanos {}
      scroll.scrollTo(scroll.maxValue)
    }
  }
  Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(Look.corner))
    .background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(scroll).padding(Look.gap)) {
    Text(text.ifEmpty { "Nothing printed so far." }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current))
  }
}

@Composable
private fun EntryDetail(state: GuiState, entry: ActivityEntry, send: (GuiIntent) -> Unit) {
  val polls = state.selectedRow?.entries?.size ?: 1
  Text(entry.tool + if (polls > 1) " ×$polls" else "", style = MaterialTheme.typography.headlineSmall)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
    Pill(entry.outcome.reading())
    when {
      entry.needsAttention -> StatusWord(Reading("Needs attention", Tone.Bad, Res.drawable.warning))
      entry.acknowledgedAt != null -> StatusWord(Reading("Acknowledged ${clock.format(entry.acknowledgedAt)}", Tone.None, Res.drawable.check_circle))
    }
  }
  entry.outcome.explanation()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  if (entry.needsAttention) {
    Btn("Acknowledge", { send(GuiIntent.Acknowledge) }, enabled = state.canAcknowledge, icon = Res.drawable.check_circle)
    Text(Wording.ACKNOWLEDGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (polls > 1) Text("$polls get_result polls of one Handle, folded into one row; every one of them is in the account.",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Field("Origin", entry.origin.words())
  Field("Workspace", entry.workspace ?: "—")
  Field("At", clock.format(entry.at))
  Field("Elapsed", entry.elapsed?.let(::took) ?: "—")
  Field("Deliveries", if (entry.deliveries == 0) "arrived once"
    else "arrived ${entry.deliveries + 1} times; the work was done once and the repeats were answered with its reply")
  Section("Arguments")
  Text(entry.arguments.ifEmpty { "none" }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current))
  Section("Recorded detail")
  Text(entry.outcome.detail ?: "Nothing is recorded yet: it is still in flight.",
    style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current))
}

@Composable
private fun Field(label: String, value: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
    Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
  }
}

/** Names the command, the Workspace, the start and the elapsed; Enter never submits (GUI-SPEC §5). */
@Composable
internal fun StopCommandDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  val running = state.stopCommandTarget ?: return
  val cancel = remember { FocusRequester() }
  val words = Wording.stopCommand(state.commandOf(running), running.workspace, clock.format(running.startedAt), state.elapsedOf(running))
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelStopCommand) },
    title = { Text("Stop command?") },
    text = { Column(verticalArrangement = Arrangement.spacedBy(Look.gap)) { words.forEach { Text(it) } } },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelStopCommand) }, Modifier.focusRequester(cancel)) },
    confirmButton = { Btn("Stop command", { send(GuiIntent.ConfirmStopCommand) }, enabled = state.inFlight == null) },
    modifier = Modifier.confirmation { send(GuiIntent.CancelStopCommand) },
  )
  LaunchedEffect(Unit) { cancel.requestFocus() }
}
