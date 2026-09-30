package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.ArgumentSpec
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.github.kzagoris.proxenos.coreapi.Outcome
import io.github.kzagoris.proxenos.coreapi.RunningCommand
import io.github.kzagoris.proxenos.coreapi.RunningOperation
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.WorkspaceId
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.FeedRow
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.absoluteRoot
import io.github.kzagoris.proxenos.frontend.askedOf
import io.github.kzagoris.proxenos.frontend.feed
import io.github.kzagoris.proxenos.frontend.folded
import io.github.kzagoris.proxenos.frontend.operationFrom
import io.github.kzagoris.proxenos.frontend.overlaps
import java.nio.file.Path

/**
 * The home screen's state, and everything a key does to it. Nothing here draws,
 * dials or waits: a key yields the next [Home] and at most one [Command] for the caller to carry
 * out, so the screen's behaviour is testable without a terminal, a Runtime or Mosaic.
 *
 * It holds **no frontend identity and no last-seen marker**. Everything it shows comes from the
 * Runtime's one ordered stream, so two TUIs attached at once show the same feed; what is local is
 * only where each one's cursor is.
 */
data class Home(
  val attachment: Attachment = Attachment.Attaching,
  val page: Page = Page.Workspaces,
  val scroll: Int = 0,
  /** Selected workspace identity. Manage never falls back to another workspace after removal. */
  val chip: WorkspaceId? = null,
  /** The stage the Review cursor rests on. */
  val stage: Stage = Stage.Runtime,
  /** The selected band or feed row, by identity, for the same reason. Null is no selection. */
  val selected: Target? = null,
  val overlay: Overlay? = null,
  /** The last thing a key led to: what was done, or why it was not. */
  val notice: Notice? = null,
  /**
   * What each running command has said so far, as last read with [ManagementAct.ReadOutput]:
   * the buffer `get_result` reads, never a copy of it kept up by this screen.
   */
  val outputs: Map<ActivityEntryId, RunningCommand> = emptyMap(),
  /**
   * This dashboard performed Stop, so the end of the stream that follows reads Stopped rather
   * than Not running. Another frontend's Stop and a crash look the same on the stream.
   */
  val stopped: Boolean = false,
) {
  val snapshot: RuntimeEvent.Snapshot? get() = (attachment as? Attachment.Attached)?.snapshot

  /** Workspaces in display order. */
  val workspaces: List<WorkspaceState> get() = snapshot?.workspaces.orEmpty()

  /** Current-run entries only. The complete Runtime snapshot remains untouched. */
  val feed: List<ActivityEntry> get() = snapshot?.feed.orEmpty()

  /**
   * The feed's rows **in the order they are drawn**, oldest at the top: consecutive `get_result`
   * polls of one Handle fold into one counted row — a display fold, never an edit,
   * so every poll is still in [feed].
   */
  val feedRows: List<FeedRow> get() = folded(feed)

  /**
   * The running-work band: every `run_command` running, oldest at the top. Bounded
   * by the Runtime-wide cap, so it cannot crowd out the feed, and empty — drawn as nothing at all
   * — when nothing is running.
   */
  val band: List<RunningOperation> get() = snapshot?.running.orEmpty().filter { it.tool == RUN_COMMAND }

  /**
   * The cursor's target list **in display order**: the band, then the feed's rows beneath it.
   * There is no other: a list walked in one order while drawn in the other moves the arrow keys
   * the wrong way.
   */
  val targets: List<Target> get() = band.map { Target.Band(it.entry) } + feedRows.map { Target.Feed(it.id) }

  val chipState: WorkspaceState? get() = workspaces.find { it.workspace.id == chip } ?: workspaces.firstOrNull().takeUnless { page == Page.Manage }

  /** The selection, if what it names is still drawn. A command that ended takes its band row with it. */
  val selectedTarget: Target? get() = selected?.takeIf { selectedIndex >= 0 }

  val selectedRow: FeedRow? get() = (selected as? Target.Feed)?.let { target -> feedRows.find { target.entry in it } }

  val selectedEntry: ActivityEntry? get() = selectedRow?.let { row -> row.entries.firstOrNull { it.needsAttention } ?: row.entries.last() }

  val selectedRunning: RunningOperation? get() = (selected as? Target.Band)?.let { target -> band.find { it.entry == target.entry } }

  private val selectedIndex: Int get() = when (val target = selected) {
    null -> -1
    is Target.Band -> band.indexOfFirst { it.entry == target.entry }
    is Target.Feed -> feedRows.indexOfFirst { target.entry in it }.let { if (it < 0) -1 else band.size + it }
  }

  /**
   * Why the Runtime is not attached, as this dashboard says it: null while it is attached or on
   * its way to being.
   */
  val absence: String? get() = when (val reason = (attachment as? Attachment.Absent)?.reason) {
    null -> null
    Reason.NotAnswering -> if (stopped) STOPPED else NOT_RUNNING
    is Reason.StartFailed -> "refused to start: ${reason.words} · [S] try again"
  }

  /**
   * Where the attachment has got to. A Runtime start id that differs from the snapshot before it
   * is a restart, and nothing selected in the old one carries over.
   */
  fun observed(next: Attachment): Home = when (next) {
    Attachment.Starting, Attachment.Attaching -> copy(attachment = next, stopped = false)
    is Attachment.Attached -> {
      val running = next.snapshot.running.map { it.entry }.toSet()
      val restarted = snapshot != null && snapshot?.start?.id != next.snapshot.start?.id
      copy(
        attachment = next, stopped = false, outputs = outputs.filterKeys { it in running },
        selected = if (restarted) null else selected,
        overlay = if (restarted) null else overlay,
        notice = if (restarted) null else notice,
        scroll = if (restarted) 0 else scroll,
      )
    }
    // What was on the screen is not kept: it would be a claim about a Runtime that is gone. A
    // start that failed is said where [S] was pressed too, not only in Review: a start that
    // fails silently reads as a key that did nothing.
    is Attachment.Absent -> copy(attachment = next, overlay = null, selected = null, outputs = emptyMap()).let { home ->
      when (val reason = next.reason) {
        is Reason.StartFailed -> home.say("The Runtime did not start. ${reason.words}", Tone.Bad)
        Reason.NotAnswering -> home
      }
    }
  }

  /** This dashboard asked the Runtime to Stop: it is detached now, and says Stopped. */
  fun stoppedHere(): Home = observed(Attachment.Absent(Reason.NotAnswering)).copy(stopped = true)

  /** What [entry] has said so far, as just read; null once it names nothing running. */
  fun read(entry: ActivityEntryId, output: RunningCommand?): Home =
    if (output == null || band.none { it.entry == entry }) copy(outputs = outputs - entry) else copy(outputs = outputs + (entry to output))

  fun press(key: Key, frame: Frame = Frame(80, 24)): Step {
    if (key.name == "PageDown") return Step(scroll(this, frame, 5))
    if (key.name == "PageUp") return Step(scroll(this, frame, -5))
    overlay?.let {
      val step = it.press(key, this)
      return if (step.home.overlay != overlay || key.name in listOf("ArrowUp", "ArrowDown")) step.copy(home = step.home.copy(scroll = 0)) else step
    }
    if (key.name == "Escape") return Step(copy(page = Page.Workspaces, notice = null, scroll = 0))
    if (key.name == "q") return Step(this, Command.Quit)
    if (key.name == "i") return Step(copy(page = Page.Review, notice = null, scroll = 0))
    // [S] stays global while the Runtime is not running: both the status line and Review's own
    // reason line point at it, so it must not be inert on the screen that points hardest.
    if (key.name == "S" && snapshot == null) return startRuntime()
    // Review is the one page that reads without a Runtime: the stage that is not running is
    // exactly what it exists to say, so its keys work with no snapshot to draw from.
    if (page == Page.Review) return when (key.name) {
      "ArrowUp", "ArrowLeft" -> Step(copy(stage = stage.previous(), scroll = 0))
      "ArrowDown", "ArrowRight" -> Step(copy(stage = stage.next(), scroll = 0))
      "Enter", "r" -> Step(copy(overlay = Overlay.StageDetail(stage), scroll = 0))
      else -> Step(this)
    }
    val state = snapshot ?: return Step(this)
    return when (page) {
      Page.Workspaces -> when (key.name) {
        "ArrowUp", "ArrowLeft" -> Step(copy(chip = chipStep(-1), scroll = 0))
        "ArrowDown", "ArrowRight" -> Step(copy(chip = chipStep(1), scroll = 0))
        "m", "Enter", "r" -> chipState?.let { Step(copy(page = Page.Manage, chip = it.workspace.id, notice = null, scroll = 0)) } ?: noWorkspace()
        "a" -> Step(copy(page = Page.Activity, notice = null, scroll = 0))
        "n" -> attachedPress(key, state)
        else -> Step(this)
      }
      Page.Manage -> if (key.name in listOf("0", "1", "2", "3", "e", "R", "w")) attachedPress(key, state) else Step(this)
      Page.Activity -> when (key.name) {
        "ArrowUp" -> Step(copy(selected = step(-1), scroll = 0))
        "ArrowDown" -> Step(copy(selected = step(1), scroll = 0))
        "Tab" -> Step(copy(selected = if (selected is Target.Band || band.isEmpty()) feedRows.lastOrNull()?.let { Target.Feed(it.id) } else Target.Band(band.first().entry), scroll = 0))
        "a", "s" -> attachedPress(key, state)
        else -> Step(this)
      }
      // Handled above, before the snapshot guard: Review navigates with no Runtime to read.
      Page.Review -> Step(this)
    }
  }

  /**
   * `S`: starting the Runtime is offered only when nothing answers on the control socket, and it
   * is only ever an ask to the caller — the process that owns the Runtime is the one that runs it.
   */
  internal fun startRuntime(): Step = when (attachment) {
    is Attachment.Absent -> Step(copy(attachment = Attachment.Starting, notice = null), Command.StartRuntime)
    else -> Step(this)
  }

  internal fun attachedPress(key: Key, snapshot: RuntimeEvent.Snapshot): Step = when (key.name) {
    "0", "1", "2", "3" -> chipState?.let { setLevel(it, AccessLevel.entries[key.name.toInt()]) } ?: noWorkspace()
    "n" -> Step(copy(overlay = Overlay.Prompt(Purpose.RegisterRoot, "")))
    "e" -> chipState?.let { Step(copy(overlay = Overlay.Prompt(Purpose.Rename(it.workspace.id), it.workspace.name))) }
      ?: noWorkspace()
    "R" -> chipState?.let { state ->
      if (!state.broken) Step(say("'${state.workspace.name}' is not Broken; there is nothing to re-confirm."))
      else Step(copy(overlay = Overlay.Confirm(Wording.reconfirm(state.workspace) + "[y] re-confirm at Read · any other key cancels", Tone.Warn, Command.Perform(
        ManagementAct.Reconfirm(state.workspace.id), "Re-confirmed '${state.workspace.name}' at Read.",
      ))))
    } ?: noWorkspace()
    "a" -> selectedEntry?.let { entry ->
      if (!entry.needsAttention) Step(say("Nothing about that entry is waiting to be Acknowledged."))
      else Step(this, Command.Perform(ManagementAct.Acknowledge(entry.id), "Acknowledged."))
    } ?: Step(say("Select an entry with ↑/↓ first."))
    "s" -> stop()
    "w" -> chipState?.let { Step(copy(overlay = Overlay.WorkspaceDetail(it.workspace.id))) } ?: noWorkspace()
    "d" -> if (snapshot.runtime.state == RuntimeState.Disconnected) {
      Step(this, Command.Perform(ManagementAct.Connect, "Connecting again."))
    } else {
      Step(this, Command.Perform(ManagementAct.Disconnect, "Disconnected. Registrations and levels are as they were."))
    }
    "C" -> if (snapshot.connectorUnconfirmed) {
      Step(this, Command.Perform(ManagementAct.AcknowledgeConnector, Wording.CONNECTOR_ACKNOWLEDGED))
    } else {
      Step(say("The connector is not Unconfirmed; there is nothing to acknowledge."))
    }
    "X" -> Step(copy(overlay = Overlay.Confirm(Wording.stopRuntime() + "[y] stop the Runtime · any other key cancels", Tone.Warn, Command.Perform(ManagementAct.Stop, "The Runtime stopped."))))
    else -> Step(this)
  }

  private fun setLevel(state: WorkspaceState, level: AccessLevel): Step {
    val workspace = state.workspace
    if (workspace.accessLevel == level) return Step(say("'${workspace.name}' is already at $level."))
    val act = Command.Perform(ManagementAct.SetLevel(workspace.id, level), "'${workspace.name}' is now at $level.")
    // Raising to Command is the one change that is confirmed: it is the one that authorises
    // work nobody is watching, and the wording is the only chance to say so before it does.
    if (level == AccessLevel.Command) return Step(copy(overlay = Overlay.Confirm(Wording.raiseToCommand(workspace) + "[y] raise to Command · any other key cancels", Tone.Bad, act)))
    return Step(this, act)
  }

  /**
   * `s` on the selected band row — or on the only one there is. It confirms rather than firing,
   * because what is ended is work whose disk effects become unknowable the moment it dies.
   */
  private fun stop(): Step {
    val running = selectedRunning ?: band.singleOrNull()
      ?: return Step(say(if (band.isEmpty()) "No command is running." else "Select a running command first: [tab] moves to the band."))
    if (running.stopping != null) return Step(say(Wording.ALREADY_STOPPING, Tone.Warn))
    return Step(copy(selected = Target.Band(running.entry), overlay = Overlay.StopCommand(running.entry)))
  }

  // Nothing selected: the first press lands on the newest feed row, which is drawn at the bottom.
  private fun step(by: Int): Target? = targets.moved(selectedIndex.takeIf { it >= 0 } ?: (targets.lastIndex - by), by)

  private fun chipStep(by: Int): WorkspaceId? =
    workspaces.moved(workspaces.indexOfFirst { it.workspace.id == chipState?.workspace?.id }.coerceAtLeast(0), by)?.workspace?.id

  private fun noWorkspace() = Step(say("No Workspace is registered yet. [n] registers one."))

  /**
   * The command line a running `run_command` was given: from what it has said so far once that
   * has been read, and until then from the arguments its entry was opened with.
   */
  fun commandOf(running: RunningOperation): String =
    outputs[running.entry]?.command ?: running.arguments.removePrefix("command=").substringBeforeLast(" cwd=")

  fun say(text: String, tone: Tone = Tone.Plain): Home = copy(notice = Notice(text, tone))

  /** What a cancelled confirmation or prompt leaves: the overlay gone, and nothing changed. */
  fun cancelled(): Home = copy(overlay = null).say(Wording.CANCELLED)
}

enum class Page { Workspaces, Manage, Activity, Review }

/**
 * One link in the chain a ChatGPT call travels, in the order they can be measured
 * and fixed: the Runtime must run before the tunnel can link, and the tunnel must link before
 * the connector matters. Each is measured on its own, and none is inferred from another.
 */
enum class Stage {
  Runtime,
  Tunnel,
  Connector,
  ;

  /** The stage one step up the chain, held at the first. */
  fun previous(): Stage = entries[(ordinal - 1).coerceAtLeast(0)]

  /** The stage one step down the chain, held at the last. */
  fun next(): Stage = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

/** A row the cursor can rest on: one in the running-work band, or one in the feed. */
sealed interface Target {
  data class Band(val entry: ActivityEntryId) : Target
  /** Names any entry of a folded row; the row is found by it. */
  data class Feed(val entry: ActivityEntryId) : Target
}

/** A key as Mosaic names it: `ArrowUp`, `Enter`, `Backspace`, or the character typed. */
@JvmInline
value class Key(val name: String)

data class Step(val home: Home, val command: Command? = null)

/** What a key asks the caller to do beyond changing the screen. */
sealed interface Command {
  data class Perform(val act: ManagementAct<*>, val done: String) : Command
  /** TryOperation, whose answer is the Operation's own outcome and is said as one. */
  data class Try(val op: Operation<*>, val tool: String, val workspace: String) : Command
  data object StartRuntime : Command
  data object Quit : Command
}

data class Notice(val text: String, val tone: Tone)

enum class Tone { Plain, Dim, Good, Warn, Bad }

/** The tone a TryOperation's outcome is said in, beside [Wording.tried]'s words for it. */
val Outcome<*>.tone: Tone get() = when (this) {
  is Outcome.Ok -> Tone.Good
  is Outcome.Failed -> Tone.Warn
  is Outcome.Uncertain -> Tone.Bad
}

/** What a Prompt's text is for. */
sealed interface Purpose {
  data object RegisterRoot : Purpose
  data class RegisterName(val root: String) : Purpose
  data class Rename(val id: WorkspaceId) : Purpose

  /**
   * One argument of a TryOperation, asked in catalog order. [given] is what was typed for the
   * ones before it; the pane it was asked from is where the answer lands.
   */
  data class TryArgument(val pane: Overlay.WorkspaceDetail, val workspace: String, val spec: OperationSpec, val index: Int, val given: Map<String, String>) : Purpose {
    val asked: List<ArgumentSpec> get() = askedOf(spec)
    val argument: ArgumentSpec get() = asked[index]
  }
}

/** Something drawn over the feed that takes every key until it is closed. */
sealed interface Overlay {
  fun press(key: Key, home: Home): Step

  /**
   * One stage's detail: what its state means, and the one action the stage owns —
   * `[S]`/`[X]` on the Runtime, `[d]` on the Tunnel, `[C]` on the Connector. Actions live here
   * rather than on the Review list, so a fix sits with the stage it fixes.
   */
  data class StageDetail(val stage: Stage) : Overlay {
    override fun press(key: Key, home: Home): Step {
      if (key.name in listOf("Escape", "i", "q")) return Step(home.copy(overlay = null))
      val snapshot = home.snapshot
      return when (stage) {
        Stage.Runtime -> when {
          key.name == "S" -> home.startRuntime()
          key.name == "X" && snapshot != null -> home.attachedPress(key, snapshot)
          else -> Step(home)
        }
        Stage.Tunnel -> if (key.name == "d" && snapshot != null) home.attachedPress(key, snapshot) else Step(home)
        Stage.Connector -> if (key.name == "C" && snapshot != null) home.attachedPress(key, snapshot) else Step(home)
      }
    }
  }

  /**
   * The per-Workspace detail pane: the catalog against this Workspace's current
   * level, and TryOperation on the entry under the cursor, in the same view.
   */
  data class WorkspaceDetail(val workspace: WorkspaceId, val tool: Int = 0) : Overlay {
    override fun press(key: Key, home: Home): Step {
      val catalog = home.snapshot?.catalog.orEmpty()
      val state = home.workspaces.find { it.workspace.id == workspace } ?: return Step(home.copy(overlay = null))
      return when (key.name) {
        "Escape", "w", "q" -> Step(home.copy(overlay = null))
        "ArrowUp" -> Step(home.copy(overlay = copy(tool = (tool - 1).coerceAtLeast(0))))
        "ArrowDown" -> Step(home.copy(overlay = copy(tool = (tool + 1).coerceAtMost((catalog.size - 1).coerceAtLeast(0)))))
        "t", "Enter" -> catalog.getOrNull(tool)?.let { spec ->
          val purpose = Purpose.TryArgument(this, state.workspace.name, spec, 0, emptyMap())
          if (purpose.asked.isEmpty()) tried(home, purpose) else Step(home.copy(overlay = Prompt(purpose, ""), notice = null))
        } ?: Step(home)
        else -> Step(home)
      }
    }
  }

  /**
   * `s`, then `y` stops; any other key cancels. The Operation is named by entry and
   * read from the stream when drawn, so the elapsed time it states is the one at the moment of
   * reading, and a command that ended while this was open is not confirmed as though it ran.
   */
  data class StopCommand(val entry: ActivityEntryId) : Overlay {
    override fun press(key: Key, home: Home): Step {
      val running = home.band.find { it.entry == entry } ?: return Step(home.copy(overlay = null).say("That command has already ended; there is nothing to stop."))
      if (key.name != "y") return Step(home.cancelled())
      if (running.stopping != null) return Step(home.copy(overlay = null).say(Wording.ALREADY_STOPPING, Tone.Warn))
      return Step(home.copy(overlay = null, notice = null), Command.Perform(ManagementAct.StopOperation(entry), Wording.stopped(home.commandOf(running))))
    }
  }

  /**
   * `y` does it; **any other key cancels**. What is being confirmed is always something the
   * wording had to say first, so a stray key must never be the one that agrees.
   */
  data class Confirm(val lines: List<String>, val tone: Tone, val yes: Command) : Overlay {
    override fun press(key: Key, home: Home): Step =
      if (key.name == "y") Step(home.copy(overlay = null, notice = null), yes)
      else Step(home.cancelled())
  }

  data class Prompt(val purpose: Purpose, val text: String) : Overlay {
    override fun press(key: Key, home: Home): Step = when {
      key.name == "Escape" -> Step(home.cancelled())
      key.name == "Backspace" -> Step(home.copy(overlay = copy(text = text.dropLast(1))))
      key.name == "Enter" -> submit(home.copy(overlay = null))
      key.name.length == 1 && !key.name[0].isISOControl() -> Step(home.copy(overlay = copy(text = text + key.name)))
      else -> Step(home)
    }

    private fun submit(home: Home): Step = when (purpose) {
      Purpose.RegisterRoot -> {
        if (text.isBlank()) return Step(home.cancelled())
        val root = absoluteRoot(text)
        Step(home.copy(overlay = Prompt(Purpose.RegisterName(root.toString()), "")))
      }
      is Purpose.RegisterName -> {
        val act = ManagementAct.Register(purpose.root, text.trim().ifEmpty { null })
        val command = Command.Perform(act, "Registered ${purpose.root} at Read.")
        val overlaps = overlaps(Path.of(purpose.root), home.workspaces)
        if (overlaps.isEmpty()) Step(home, command)
        else Step(home.copy(overlay = Confirm(Wording.overlap(purpose.root, overlaps) + "[y] register anyway, at Read · any other key cancels", Tone.Warn, command)))
      }
      is Purpose.Rename -> {
        val name = text.trim()
        if (name.isEmpty()) Step(home.say("A Workspace needs a name; nothing was changed."))
        else Step(home, Command.Perform(ManagementAct.Rename(purpose.id, name), "Renamed to '$name'."))
      }
      is Purpose.TryArgument -> {
        val argument = purpose.argument
        val back = home.copy(overlay = purpose.pane)
        if (text.isEmpty() && argument.required) return Step(back.say("'${argument.name}' is required; nothing was tried."))
        val given = if (text.isEmpty()) purpose.given else purpose.given + (argument.name to text)
        val next = purpose.copy(index = purpose.index + 1, given = given)
        if (next.index < next.asked.size) Step(home.copy(overlay = Prompt(next, ""))) else tried(back, next)
      }
    }
  }
}

/** Every argument asked: the Operation built, and handed to the caller to try with the pane still open. */
private fun tried(home: Home, purpose: Purpose.TryArgument): Step {
  val back = home.copy(overlay = purpose.pane)
  return try {
    Step(back.say("Trying ${purpose.spec.name} against '${purpose.workspace}'…", Tone.Dim), Command.Try(operationFrom(CALLER, purpose.spec.name, purpose.workspace, purpose.given), purpose.spec.name, purpose.workspace))
  } catch (refused: IllegalArgumentException) {
    Step(back.say("${refused.message} Nothing was tried."))
  }
}

/** What this dashboard's `request_id`s start with: a TryOperation from here is a `tui-` one. */
private const val CALLER = "tui"

/** The element [by] places from [from], held at either end; null for an empty list. */
private fun <T> List<T>.moved(from: Int, by: Int): T? = if (isEmpty()) null else this[(from + by).coerceIn(0, lastIndex)]
