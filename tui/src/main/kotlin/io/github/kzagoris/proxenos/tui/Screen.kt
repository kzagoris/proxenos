package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityOutcome
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeStartId
import io.github.kzagoris.proxenos.coreapi.RunningOperation
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.TunnelComplaint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A run of text drawn in one tone. */
data class Span(val text: String, val tone: Tone = Tone.Plain, val selected: Boolean = false)

/** One terminal line. [plain] is what `--dump` prints and what a test reads. */
data class Line(val spans: List<Span>) {
  constructor(text: String, tone: Tone = Tone.Plain) : this(listOf(Span(text, tone)))

  val plain: String get() = spans.joinToString("") { it.text }
}

/** The size the screen is drawn to, and the clock and zone its times are read in. */
data class Frame(
  val columns: Int,
  val rows: Int,
  val zone: ZoneId = ZoneId.systemDefault(),
  val now: Instant = Instant.now(),
)

/** Each screen owns its content; details replace it until dismissed. */
private data class Layout(
  val heading: List<Line>, val notice: List<Line>, val footer: List<Line>,
  val body: List<Line>, val room: Int,
) {
  val maxScroll: Int get() = (body.size - room).coerceAtLeast(0)
  fun offset(home: Home): Int {
    val chosen = body.indexOfFirst { line -> line.spans.any { it.selected } }
    return if (home.scroll == 0 && chosen >= room) (chosen - room / 2).coerceAtMost(maxScroll)
      else home.scroll.coerceAtMost(maxScroll)
  }
}

private fun layout(home: Home, frame: Frame): Layout {
  val footer = paragraph(help(home), frame.columns - 2, Tone.Dim)
  // Review's rows are the status, so the three-segment line is not drawn twice on that screen.
  val heading = buildList {
    add(Line(if (home.page == Page.Review || home.overlay != null) "Review" else home.page.name))
    if (home.page != Page.Review) add(statusLine(home))
  }
  val notice = home.notice?.let { paragraph(it.text, frame.columns - 4, it.tone) }.orEmpty()
  val room = (frame.rows - heading.size - footer.size - notice.size - 1).coerceAtLeast(1)
  val body = home.overlay?.let { overlay(it, home, frame) } ?: when (home.page) {
    Page.Workspaces -> workspaceList(home, frame)
    Page.Manage -> manage(home, frame)
    Page.Review -> reviewPage(home, frame)
    Page.Activity -> {
      val running = band(home, frame)
      running + feed(home, frame, (room - running.size).coerceAtLeast(3))
    }
  }
  return Layout(heading, notice, footer, body, room)
}

internal fun scroll(home: Home, frame: Frame, by: Int): Home {
  val layout = layout(home, frame)
  return home.copy(scroll = (layout.offset(home) + by).coerceIn(0, layout.maxScroll))
}

fun render(home: Home, frame: Frame): List<Line> {
  val layout = layout(home, frame)
  val (heading, notice, footer, body, room) = layout
  val offset = layout.offset(home)
  val visible = body.drop(offset).take(room)
  val paging = if (body.size > room) listOf(Line("[PgUp/PgDn] scroll", Tone.Dim)) else emptyList()
  val available = (frame.rows - heading.size - footer.size - paging.size).coerceAtLeast(0)
  return (heading + (notice + visible).take(available) + paging + footer)
    .take(frame.rows.coerceAtLeast(1)).map { it.fitted(frame.columns) }
}

/**
 * The three links, one line, in the order of the chain a call travels. Each is
 * measured on its own: the Runtime's attachment to this dashboard, the Runtime's tunnel link,
 * and the connector. What cannot be measured yet reads Can't tell, never a fault, and `[i]`
 * appears as the way to Review whenever one of them is worth reading about.
 */
private fun statusLine(home: Home): Line {
  val spans = mutableListOf<Span>()
  for (stage in Stage.entries) {
    if (spans.isNotEmpty()) spans += Span("   ")
    val (row, tone) = stageRow(home, stage)
    spans += Span(row, tone)
  }
  if (needsReview(home)) spans += Span("   [i] Review", Tone.Dim)
  return Line(spans)
}

/** Whether Review has something to say: a stage in trouble, or one never confirmed. */
private fun needsReview(home: Home): Boolean {
  val state = home.snapshot?.runtime?.state
  return home.attachment is Attachment.Absent || tunnelTrouble(state) || home.snapshot?.connectorUnconfirmed == true
}

/** A tunnel that is not quietly Connected or Disconnected. */
private fun tunnelTrouble(state: RuntimeState?): Boolean = state == RuntimeState.Connecting || state is RuntimeState.Failed

private fun workspaceList(home: Home, frame: Frame): List<Line> = buildList {
  val snapshot = home.snapshot ?: return@buildList
  // One Review action for a connection problem. The connector does not banner
  // here: it is the Connector row's own state in the status line and in Review. Unlike the
  // status line's hint, this waits for the credential panel's words, so a bare Connecting —
  // nothing to quote yet — stays out of the workspace list.
  val tunnel = snapshot.runtime.state
  if (tunnel is RuntimeState.Failed || (tunnel == RuntimeState.Connecting && snapshot.connectingWords != null))
    add(Line("Tunnel needs attention · [i] Review", Tone.Warn))
  val unresolved = home.feed.count { it.needsAttention }
  if (home.band.isNotEmpty() || unresolved > 0)
    add(Line("Activity · ${home.band.size} running · $unresolved need attention · [a] Open", Tone.Warn))
  if (home.workspaces.isEmpty()) add(Line("No workspaces yet · [n] Add", Tone.Dim))
  home.workspaces.forEach { state ->
    val workspace = state.workspace
    val selected = state == home.chipState
    add(Line(listOf(Span("${if (selected) ">" else " "} ${workspace.name} · ${workspace.accessLevel}", Tone.Plain, selected))))
    addAll(paragraph(workspace.root, frame.columns - 4, Tone.Dim))
    if (state.broken) add(Line("  Workspace unavailable · [Enter] Review", Tone.Bad))
    if (home.band.any { it.workspace == workspace.name } && workspace.accessLevel != AccessLevel.Command)
      add(Line("  Command still running after access changed · [a] Review", Tone.Warn))
  }
}

private fun manage(home: Home, frame: Frame): List<Line> = buildList {
  val state = home.chipState
  if (state == null) {
    add(Line("Workspace no longer registered.", Tone.Dim))
    return@buildList
  }
  val workspace = state.workspace
  add(Line(workspace.name))
  addAll(paragraph(workspace.root, frame.columns - 4, Tone.Dim))
  add(Line("Access · ${workspace.accessLevel}"))
  if (state.broken) {
    add(Line("Workspace unavailable", Tone.Bad))
    addAll(paragraph("The registered directory could not be verified. It may have changed identity or become inaccessible. ChatGPT cannot use this workspace.", frame.columns - 4))
    addAll(paragraph("Check the directory, then [R] re-confirm it. Re-confirming sets access to Read.", frame.columns - 4, Tone.Warn))
  }
  add(Line(""))
  add(Line("[0] None   [1] Read   [2] Write   [3] Command"))
  add(Line("[e] Rename   [w] Tools"))
  if (home.band.any { it.workspace == workspace.name } && workspace.accessLevel != AccessLevel.Command)
    add(Line("Command still running · open Activity to review or stop it.", Tone.Warn))
}

/**
 * Review: one row per link in the chain a ChatGPT call travels, carrying its own
 * state, and a stage that cannot be measured yet — the tunnel and the connector while the
 * Runtime is not running — reads Can't tell rather than broken. `[Enter]` opens the selected
 * stage's detail, where that stage's actions live.
 */
private fun reviewPage(home: Home, frame: Frame): List<Line> = buildList {
  val width = frame.columns - 4
  for (stage in Stage.entries) {
    val selected = stage == home.stage
    val (row, tone) = stageRow(home, stage)
    add(Line(listOf(Span(if (selected) "> " else "  ", Tone.Plain, selected), Span(row, tone, selected))))
    stageHint(home, stage)?.let { addAll(hint(it, width)) }
  }
}

/** The stage's row as drawn, in the status line and the Review list alike: name, dot, state. */
private fun stageRow(home: Home, stage: Stage): Pair<String, Tone> =
  stageReading(home, stage).let { (state, tone) -> "${stage.name} · $state" to tone }

/** What one stage reads, and in which tone: the words its row, its detail and the status line carry. */
private fun stageReading(home: Home, stage: Stage): Pair<String, Tone> = when (stage) {
  Stage.Runtime -> when (home.attachment) {
    Attachment.Attaching -> "Attaching…" to Tone.Dim
    Attachment.Starting -> "Starting…" to Tone.Dim
    is Attachment.Absent -> "Not running" to Tone.Warn
    is Attachment.Attached -> "Attached" to Tone.Good
  }
  Stage.Tunnel -> when (val state = home.snapshot?.runtime?.state) {
    null -> Wording.CANT_TELL to Tone.Dim
    RuntimeState.Connected -> "Connected" to Tone.Good
    RuntimeState.Connecting -> "Connecting" to Tone.Warn
    is RuntimeState.Failed -> "Failed" to Tone.Bad
    RuntimeState.Disconnected -> "Disconnected" to Tone.Dim
  }
  Stage.Connector -> when (val snapshot = home.snapshot) {
    null -> Wording.CANT_TELL to Tone.Dim
    else -> if (snapshot.connectorUnconfirmed) "Unconfirmed" to Tone.Warn else "Confirmed" to Tone.Good
  }
}

/** The one line a stage that is not healthy adds under its row: why, in the machine's own words. */
private fun stageHint(home: Home, stage: Stage): String? {
  val state = home.snapshot?.runtime?.state
  return when {
    stage == Stage.Runtime && home.attachment is Attachment.Absent -> home.attachment.why
    stage == Stage.Tunnel && state is RuntimeState.Failed -> complaint(state.complaint)
    else -> null
  }
}

/** [text] wrapped to [width] and indented four columns: a row's reason, not a second row. */
private fun hint(text: String, width: Int): List<Line> =
  paragraph(text, (width - 2).coerceAtLeast(20), Tone.Dim).map { Line("  ${it.plain}", Tone.Dim) }

/** The Runtime stage's detail: what this dashboard's attachment is, and the autostart rule. */
private fun runtimeDetail(home: Home, frame: Frame): List<Line> {
  val width = frame.columns - 4
  return buildList {
    when (val attachment = home.attachment) {
      Attachment.Attaching -> addAll(paragraph(Wording.ATTACHING, width))
      Attachment.Starting -> addAll(paragraph(Wording.STARTING, width))
      is Attachment.Absent -> addAll(paragraph("${stageReading(home, Stage.Runtime).first}: ${attachment.why}", width, Tone.Warn))
      is Attachment.Attached -> {
        attachment.snapshot.start?.let { addAll(paragraph("This Runtime started ${clock(it.at, frame)}.", width)) }
        addAll(paragraph(Wording.ATTACHED, width))
      }
    }
    addAll(paragraph(Wording.NO_AUTOSTART, width))
  }
}

/** The Tunnel stage's detail: what the link's state means, the tunnel's own words, and the caveat. */
private fun tunnelDetail(home: Home, frame: Frame): List<Line> {
  val width = frame.columns - 4
  val snapshot = home.snapshot
  return buildList {
    if (snapshot == null) addAll(paragraph(Wording.CANT_TELL_TUNNEL, width, Tone.Dim))
    else when (val state = snapshot.runtime.state) {
      RuntimeState.Connecting -> {
        addAll(paragraph("It is Connecting: no poll has reached the tunnel since it started, or since you last connected it.", width))
        snapshot.connectingWords?.let { words ->
          Wording.connecting(clock(snapshot.runtime.enteredAt, frame), words).forEach { addAll(paragraph(it, width, Tone.Warn)) }
        }
      }
      RuntimeState.Connected -> addAll(paragraph("It is Connected.", width))
      is RuntimeState.Failed -> addAll(
        paragraph(
          "It is Failed: the link to the tunnel worked and has gone stale. That is a problem, not a choice anyone made. ${complaint(state.complaint)}",
          width, Tone.Bad,
        ),
      )
      RuntimeState.Disconnected -> addAll(paragraph("It is Disconnected, because you disconnected it. That is your decision, not a fault; [d] connects it again.", width))
    }
    // This is the meaning of the signal, kept in every state — not an annotation of
    // the Connected reading alone.
    addAll(paragraph(Wording.CONNECTED_CAVEAT, width))
  }
}

/** The Connector stage's detail: the literal steps while Unconfirmed, and what Confirmed can mean. */
private fun connectorDetail(home: Home, frame: Frame): List<Line> {
  val width = frame.columns - 4
  val snapshot = home.snapshot ?: return paragraph(Wording.CANT_TELL_CONNECTOR, width, Tone.Dim)
  return if (snapshot.connectorUnconfirmed) Wording.UNCONFIRMED.flatMap { paragraph(it, width, Tone.Warn) }
  else paragraph(Wording.CONNECTOR_CONFIRMED, width)
}

/**
 * The running-work band, between the banners and the feed — and nothing at all when
 * nothing is running: the screen changing shape is the signal. Each row carries the last line
 * the command printed; the selected one expands in place to the buffer `get_result` reads,
 * labelled so the screen and the tool agree about what exists. Bounded by the cap, and the
 * expansion by a quarter of the screen, so it cannot crowd out the feed.
 */
private fun band(home: Home, frame: Frame): List<Line> {
  val band = home.band
  if (band.isEmpty()) return emptyList()
  val cap = Operation.COMMAND_CONCURRENCY_CAP
  return buildList {
    add(Line("Running · ${band.size}/$cap commands · [Tab] Select · [s] Stop", Tone.Warn))
    val selected = home.selectedRunning?.entry
    for (running in band) {
      val chosen = running.entry == selected
      add(bandRow(home, running, chosen, frame))
      if (chosen) {
        addAll(expanded(home, running, frame))
        val state = home.workspaces.find { it.workspace.name == running.workspace }
        if (state != null && state.workspace.accessLevel != AccessLevel.Command)
          addAll(paragraph(Wording.lowered(home.commandOf(running), state.workspace.name, state.workspace.accessLevel, clock(running.startedAt, frame), running.promoted), frame.columns - 4, Tone.Warn))
      }
    }
  }
}

private fun bandRow(home: Home, running: RunningOperation, selected: Boolean, frame: Frame): Line {
  val gutter = if (selected) " >" else "  "
  val lead = "$gutter ${clock(running.startedAt, frame)}  ${(running.workspace ?: "—").take(12).padEnd(12)}  "
  val state = running.stopping?.let { Wording.stopping(it) }
    ?: ("running ${elapsed(running.startedAt, frame)}" + if (running.promoted) " · Promoted, Handle ${running.entry.value}" else "")
  val output = home.outputs[running.entry]
  val last = when {
    output == null -> ""
    else -> output.outputSoFar.lineSequence().lastOrNull { it.isNotBlank() }?.let { " │ ${it.trim()}" } ?: " │ (nothing printed yet)"
  }
  // The state first, then the command cut to leave the last line room: the line is what is
  // watched, and the whole command is named when the row is expanded or stopped.
  val room = frame.columns - lead.length - state.length - 4
  val command = home.commandOf(running).let { if (last.isNotEmpty() && it.length > room / 2) it.take((room / 2 - 1).coerceAtLeast(8)) + "…" else it }
  return Line(
    listOf(
      Span(lead, Tone.Plain, selected),
      Span(state, if (running.stopping != null) Tone.Bad else Tone.Warn, selected),
      Span("  `$command`", Tone.Plain, selected),
      Span(last, Tone.Dim, selected),
    ),
  )
}

/** The selected row's whole tail: the one buffer, as much of it as fits, and a label saying which part that is. */
private fun expanded(home: Home, running: RunningOperation, frame: Frame): List<Line> {
  val output = home.outputs[running.entry] ?: return listOf(Line("      reading what it has printed…", Tone.Dim))
  val lines = output.outputSoFar.removeSuffix("\n").let { if (it.isEmpty()) emptyList() else it.lines() }
  val room = (frame.rows / 4).coerceAtLeast(3)
  val shown = lines.takeLast(room)
  val source = if (running.promoted) "the buffer get_result returns for Handle ${running.entry.value}"
  else "the buffer its reply will carry, and get_result will return if it is Promoted"
  val dropped = if (output.droppedBytes > 0) ", ${output.droppedBytes} bytes dropped from its middle by the 64 KiB bound" else ""
  val label = when {
    lines.isEmpty() -> "      ┌ $source: nothing printed so far"
    shown.size < lines.size -> "      ┌ $source: ${lines.size} lines$dropped · the last ${shown.size} shown"
    else -> "      ┌ $source: ${lines.size} line${if (lines.size == 1) "" else "s"}$dropped · all shown"
  }
  return listOf(Line(label, Tone.Dim)) + shown.map { Line("      │ $it") }
}

/** The tunnel's own words, quoted field by field, and never a diagnosis of ours. */
fun complaint(complaint: TunnelComplaint?): String {
  if (complaint == null) return "The tunnel said nothing about why."
  val said = listOfNotNull(
    complaint.statusCode?.let { "status_code $it" },
    complaint.errorCode?.let { "error_code \"$it\"" },
    complaint.message?.let { "message \"$it\"" },
    complaint.mitigation?.let { "mitigation \"$it\"" },
  )
  return if (said.isEmpty()) "The tunnel said nothing about why." else "The tunnel said: ${said.joinToString(" · ")}"
}

private fun overlay(overlay: Overlay, home: Home, frame: Frame): List<Line> {
  val width = frame.columns - 4
  return when (overlay) {
    is Overlay.StageDetail -> listOf(Line(""), Line("  ${overlay.stage.name} detail · [esc] close")) +
      when (overlay.stage) {
        Stage.Runtime -> runtimeDetail(home, frame)
        Stage.Tunnel -> tunnelDetail(home, frame)
        Stage.Connector -> connectorDetail(home, frame)
      } + Line("")
    is Overlay.WorkspaceDetail -> workspaceDetail(overlay, home, frame)
    is Overlay.StopCommand -> {
      val running = home.band.find { it.entry == overlay.entry }
      if (running == null) listOf(Line(""), Line("  That command has already ended. [any key] close", Tone.Dim), Line(""))
      else buildList {
        add(Line(""))
        val lines = Wording.stopCommand(home.commandOf(running), running.workspace, clock(running.startedAt, frame), elapsed(running.startedAt, frame))
        lines.forEachIndexed { index, text -> addAll(paragraph(text, width, if (index == 0 || index == lines.lastIndex) Tone.Bad else Tone.Plain)) }
        add(Line(""))
      }
    }
    is Overlay.Confirm -> buildList {
      add(Line(""))
      overlay.lines.forEachIndexed { index, text ->
        addAll(paragraph(text, width, if (index == 0 || index == overlay.lines.lastIndex) overlay.tone else Tone.Plain))
      }
      add(Line(""))
    }
    is Overlay.Prompt -> {
      val label = when (overlay.purpose) {
        Purpose.RegisterRoot -> "Register a directory. Root"
        is Purpose.RegisterName -> "Name for ${overlay.purpose.root} (enter keeps its directory name)"
        is Purpose.Rename -> "Rename to"
        is Purpose.TryArgument -> with(overlay.purpose) {
          "Try ${spec.name} against '$workspace' · ${argument.name} (${if (argument.required) "required" else "enter leaves it out"}; ${argument.description})"
        }
      }
      listOf(Line(""), Line("  $label: ${overlay.text}▏"), Line("  [enter] done · [esc] cancel", Tone.Dim), Line(""))
    }
  }
}

/**
 * The per-Workspace detail pane: the whole catalog against this Workspace's current
 * level, each entry permitted or not and why, with TryOperation on the entry under the cursor.
 */
private fun workspaceDetail(pane: Overlay.WorkspaceDetail, home: Home, frame: Frame): List<Line> {
  val width = frame.columns - 4
  val state = home.workspaces.find { it.workspace.id == pane.workspace } ?: return listOf(Line("  That Workspace is no longer registered.", Tone.Dim))
  val workspace = state.workspace
  val catalog = home.snapshot?.catalog.orEmpty()
  return buildList {
    add(Line(""))
    add(Line("  '${workspace.name}' at ${if (state.broken) "Broken, was ${workspace.accessLevel}" else workspace.accessLevel} · " +
      "the ${catalog.size} tools ChatGPT is served, against it · [↑↓] tool · [t] try it · [esc] close"))
    catalog.forEachIndexed { index, spec ->
      val (admitted, why) = Wording.admission(spec, state)
      val selected = index == pane.tool
      add(
        Line(
          listOf(
            Span(if (selected) "  > " else "    ", Tone.Plain, selected),
            Span(if (admitted) "✓ " else "✗ ", if (admitted) Tone.Good else Tone.Bad, selected),
            Span(spec.name.padEnd(16), Tone.Plain, selected),
            Span(why, if (admitted) Tone.Plain else Tone.Dim, selected),
          ),
        ),
      )
      val notes = Wording.notes(spec, workspace)
      val tone = if (spec.name == RUN_COMMAND) Tone.Warn else Tone.Dim
      if (selected) notes.forEach { note -> addAll(paragraph(note, width - 6, tone).map { Line("      ${it.plain.trim()}", tone) }) }
      if (selected) addAll(paragraph(spec.description, width - 6, Tone.Dim).map { Line("      ${it.plain.trim()}", Tone.Dim) })
    }
    add(Line(""))
  }
}

/**
 * The feed, oldest at the top, windowed to [rows] around the selection — or to the newest when
 * nothing is selected. The only boundary drawn is a Runtime start: there is no "since you last
 * looked", because nothing here knows who looked.
 */
private fun feed(home: Home, frame: Frame, rows: Int): List<Line> {
  val snapshot = home.snapshot ?: return listOf(Line("Activity · nothing to show until the Runtime runs", Tone.Dim))
  val entries = home.feed
  val unresolved = entries.count { it.needsAttention }
  val header = if (unresolved == 0) Line("Activity · nothing unresolved", Tone.Dim)
  else Line("Activity · $unresolved unresolved, flagged ! · [↑↓] select · [a] acknowledge", Tone.Warn)

  val body = mutableListOf<Line>()
  var selectedEnd = -1
  var start: RuntimeStartId? = null
  val chosen = home.selectedRow
  for (feedRow in home.feedRows) {
    val entry = feedRow.entries.last()
    if (entry.runtimeStart != start) {
      start = entry.runtimeStart
      body += boundary(entry.runtimeStart, snapshot, frame)
    }
    val selected = feedRow == chosen
    body += row(feedRow, selected, frame)
    if (selected) {
      if (feedRow.entries.any { it.outcome is ActivityOutcome.Undelivered }) body += paragraph(Wording.UNDELIVERED, frame.columns - 10, Tone.Warn).map { Line("    ${it.plain}", Tone.Warn) }
      selectedEnd = body.size
    }
  }
  // This start's boundary is drawn even before it has an entry: it is where the next one lands.
  val current = snapshot.start
  if (current != null && start != current.id) body += boundary(current.id, snapshot, frame)
  if (entries.isEmpty()) body += Line("  nothing recorded yet", Tone.Dim)

  val room = (rows - 1).coerceAtLeast(1)
  // The newest page, unless the selection has been walked above it: then the page ends on it.
  val end = if (selectedEnd < 0 || selectedEnd > body.size - room) body.size else selectedEnd
  val window = body.subList((end - room).coerceAtLeast(0), end)
  return listOf(header) + window
}

private fun boundary(start: RuntimeStartId, snapshot: RuntimeEvent.Snapshot, frame: Frame): Line {
  val current = snapshot.start
  val label = if (current != null && current.id == start) "Runtime started ${clock(current.at, frame)} · this start"
  else "an earlier Runtime start"
  val rule = "── $label "
  return Line(rule + "─".repeat((frame.columns - rule.length).coerceIn(2, 40)), Tone.Dim)
}

private fun row(feedRow: FeedRow, selected: Boolean, frame: Frame): Line {
  val entry = feedRow.entries.last()
  val attention = feedRow.entries.any { it.needsAttention }
  // The gutter: `!` for an unresolved outcome nobody has settled, and the cursor beside it.
  val gutter = (if (attention) "!" else " ") + (if (selected) ">" else " ")
  val origin = if (entry.origin == Origin.ChatGpt) "ChatGPT " else "frontend"
  val outcome = buildString {
    append(entry.outcome.said)
    entry.elapsed?.let { append(" ").append(it.inWholeMilliseconds.let { ms -> if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0) }) }
    if (entry.deliveries > 0) append(" · +${entry.deliveries} deliver${if (entry.deliveries == 1) "y" else "ies"}")
    if (entry.outcome.unresolved && entry.acknowledgedAt != null) append(" · acknowledged")
  }
  val tone = when {
    attention -> Tone.Bad
    entry.outcome is ActivityOutcome.Failed -> Tone.Warn
    entry.outcome == ActivityOutcome.InFlight -> Tone.Plain
    else -> Tone.Plain
  }
  // A fold is counted, never hidden: every poll is still in the account, and this says how many.
  val tool = if (feedRow.entries.size > 1) "${entry.tool} ×${feedRow.entries.size}" else entry.tool
  val lead = "$gutter ${clock(entry.at, frame)}  $origin  ${(entry.workspace ?: "—").take(12).padEnd(12)}  ${tool.padEnd(13)} "
  val room = (frame.columns - lead.length - outcome.length - 2).coerceAtLeast(0)
  val arguments = entry.arguments.replace('\n', ' ').let { if (it.length > room) it.take((room - 1).coerceAtLeast(0)) + "…" else it }
  return Line(
    listOf(
      Span(gutter, if (attention) Tone.Bad else Tone.Plain, selected),
      Span(lead.drop(gutter.length), Tone.Plain, selected),
      Span(arguments.padEnd(room), Tone.Dim, selected),
      Span("  $outcome", tone, selected),
    ),
  )
}

private fun help(home: Home): String = when (val overlay = home.overlay) {
  is Overlay.StageDetail -> when (overlay.stage) {
    Stage.Runtime -> if (home.snapshot == null) "[S] Start Runtime · [Esc] Back" else "[X] Stop Runtime · [Esc] Back"
    Stage.Tunnel -> when {
      home.snapshot == null -> "[Esc] Back"
      home.snapshot?.runtime?.state == RuntimeState.Disconnected -> "[d] Connect tunnel · [Esc] Back"
      else -> "[d] Disconnect tunnel · [Esc] Back"
    }
    Stage.Connector -> if (home.snapshot?.connectorUnconfirmed == true) "[C] Confirm setup · [Esc] Back" else "[Esc] Back"
  }
  is Overlay.Confirm, is Overlay.StopCommand -> "[y] Confirm · [Esc] Cancel"
  null -> when {
    home.page == Page.Review -> REVIEW_HELP
    home.snapshot == null -> "[S] Start Runtime · [i] Review · [q] Close dashboard"
    else -> when (home.page) {
      Page.Workspaces -> "[↑↓] Select · [n] Add · [m] Manage · [a] Activity · [i] Review · [q] Close dashboard"
      Page.Manage -> "[Esc] Workspaces · [q] Close dashboard"
      Page.Activity -> "[↑↓] Select · [Tab] Running · [a] Acknowledge · [s] Stop command · [Esc] Workspaces"
      Page.Review -> REVIEW_HELP
    }
  }
  else -> "[Esc] Back"
}

private const val REVIEW_HELP = "[↑↓] Stage · [Enter] Details · [Esc] Workspaces · [q] Close dashboard"

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd HH:mm")

/** How long since [since], as the band and the stop confirmation say it: `42s`, `3m05s`, `2h01m`. */
private fun elapsed(since: Instant, frame: Frame): String {
  val seconds = Duration.between(since, frame.now).seconds.coerceAtLeast(0)
  return when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "%dm%02ds".format(seconds / 60, seconds % 60)
    else -> "%dh%02dm".format(seconds / 3600, seconds % 3600 / 60)
  }
}

private fun clock(at: Instant, frame: Frame): String {
  val local = at.atZone(frame.zone)
  return if (local.toLocalDate() == LocalDate.ofInstant(frame.now, frame.zone)) TIME.format(local) else DATE_TIME.format(local).padEnd(8)
}

/** [text] wrapped at word boundaries to [width], indented two columns. */
fun paragraph(text: String, width: Int, tone: Tone = Tone.Plain): List<Line> {
  val lines = mutableListOf<String>()
  var current = StringBuilder()
  for (word in text.split(' ')) {
    if (current.isNotEmpty() && current.length + 1 + word.length > width.coerceAtLeast(20)) {
      lines += current.toString()
      current = StringBuilder()
    }
    if (current.isNotEmpty()) current.append(' ')
    current.append(word)
  }
  if (current.isNotEmpty() || lines.isEmpty()) lines += current.toString()
  return lines.map { Line("  $it", tone) }
}

/** Cut to the terminal's width, so a long line never wraps and pushes the screen down. */
private fun Line.fitted(columns: Int): Line {
  var left = columns
  val kept = mutableListOf<Span>()
  for (span in spans) {
    if (left <= 0) break
    kept += if (span.text.length <= left) span else span.copy(text = span.text.take(left))
    left -= span.text.length
  }
  return Line(kept)
}
