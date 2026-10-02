package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.coreapi.RunningCommand
import io.github.kzagoris.proxenos.coreapi.RunningOperation
import io.github.kzagoris.proxenos.coreapi.WorkspaceId
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.FeedRow
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.commandOf
import io.github.kzagoris.proxenos.frontend.feed
import io.github.kzagoris.proxenos.frontend.folded
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording

/** The sidebar's three places, in Ctrl+1/2/3 order (GUI-SPEC §4.1). */
enum class Destination { Workspaces, Activity, Connection }

/** The chain a ChatGPT call travels, each stage measured on its own (ADR 0009). */
enum class Stage { Runtime, Tunnel, Connector }

enum class Registration { Rename, Move, Reconfirm, Forget }

/** The Try form open on one catalog entry for the selected Workspace, and its last answer. */
data class Trying(val tool: String, val result: Tried? = null) {
  /**
   * The newest entry that can be the last try's, once the stream has carried it: one of [tool]'s
   * against the Workspace it named that was not there before it, carrying its key if it has one.
   * [activity] is oldest first.
   */
  fun entryIn(activity: List<ActivityEntry>): ActivityEntryId? {
    val tried = result ?: return null
    return activity.lastOrNull {
      it.id !in tried.before && it.origin == Origin.Frontend && it.tool == tool && it.workspace == tried.workspace &&
        (tried.key == null || it.arguments.endsWith("${Operation.KEY_ARGUMENT}=${tried.key}"))
    }?.id
  }
}

/** A try's answer in the pipeline's [words], and what [Trying.entryIn] finds its entry by. */
data class Tried(
  val words: String,
  val tone: Tone,
  val workspace: String,
  val key: String?,
  val before: Set<ActivityEntryId>,
)

data class GuiState(
  val attachment: Attachment = Attachment.Attaching,
  val stopConfirmation: Boolean = false,
  val inFlight: ManagementAct<*>? = null,
  /** An act's outcome for the snackbar. */
  val notice: String? = null,
  /** An act's refusal, in the Runtime's words, for the dialog that is still open. */
  val refusal: String? = null,
  val stopped: Boolean = false,
  val destination: Destination = Destination.Workspaces,
  /** The stage Connection details; null is the list alone in the compact layout. */
  val stage: Stage? = null,
  val adding: Boolean = false,
  val registration: Registration? = null,
  /** Kept when forgotten, so the detail says gone instead of selecting a neighbour. */
  val workspace: WorkspaceId? = null,
  /**
   * The running command or feed row Activity details, by entry id. A running command keeps its id
   * when it ends, so the selection turns into its entry (GUI-SPEC §3.4).
   */
  val activity: ActivityEntryId? = null,
  val commandConfirmation: WorkspaceId? = null,
  val trying: Trying? = null,
  /** The running command whose Stop is being confirmed. */
  val stopCommandConfirmation: ActivityEntryId? = null,
  /**
   * What each running command has said, as last read with ReadOutput: the buffer `get_result`
   * reads. Null once a read came back null; that command is polled no more (GUI-SPEC §3.5).
   */
  val outputs: Map<ActivityEntryId, RunningCommand?> = emptyMap(),
  /** The entry the snackbar's Show entry opens. */
  val noticeEntry: ActivityEntryId? = null,
) {
  val snapshot: RuntimeEvent.Snapshot? get() = (attachment as? Attachment.Attached)?.snapshot
  val canStart: Boolean get() = attachment is Attachment.Absent && inFlight == null
  val canStop: Boolean get() = snapshot != null && inFlight == null
  val canAdd: Boolean get() = snapshot != null && inFlight == null
  val canConnect: Boolean get() = snapshot?.runtime?.state == RuntimeState.Disconnected && inFlight == null && !dialogOpen
  val canDisconnect: Boolean get() = snapshot != null && snapshot?.runtime?.state != RuntimeState.Disconnected && inFlight == null && !dialogOpen
  val canAcknowledgeConnector: Boolean get() = snapshot?.connectorUnconfirmed == true && inFlight == null && !dialogOpen
  val selectedWorkspace: WorkspaceState? get() = snapshot?.workspaces?.find { it.workspace.id == workspace }
  /** Setting its level, opening a registration dialog or a Try form. */
  val canActOnWorkspace: Boolean get() = selectedWorkspace != null && inFlight == null && !dialogOpen
  val dialogOpen: Boolean get() = stopConfirmation || adding || commandConfirmation != null || registration != null || trying != null ||
    stopCommandConfirmation != null

  /** A destination showing its detail, which Esc and the compact Back leave (GUI-SPEC §5). */
  val detailOpen: Boolean get() = when (destination) {
    Destination.Workspaces -> workspace != null
    Destination.Activity -> activity != null
    Destination.Connection -> stage != null
  }

  val triedEntry: ActivityEntryId? get() = trying?.entryIn(snapshot?.activity.orEmpty())

  /** Running now: every `run_command` still running, oldest first, at most four. */
  val running: List<RunningOperation> get() = snapshot?.running.orEmpty().filter { it.tool == RUN_COMMAND }

  /**
   * This start's feed as drawn: newest first, consecutive `get_result` polls folded into one
   * counted row. Folded once per state, and only if something reads it.
   */
  val feedRows: List<FeedRow> by lazy { folded(snapshot?.feed.orEmpty()).asReversed() }

  val selectedRunning: RunningOperation? get() = running.find { it.entry == activity }
  val selectedRow: FeedRow? get() = activity?.let { id -> feedRows.find { id in it } }
  val selectedEntry: ActivityEntry? get() = selectedRow?.detailed

  /** The running command whose Stop is being confirmed, while it is still running. */
  val stopCommandTarget: RunningOperation? get() = running.find { it.entry == stopCommandConfirmation }

  /** Read once a second while Activity shows (GUI-SPEC §3.5); a command whose read came back null is not. */
  val polled: List<ActivityEntryId> get() =
    if (destination != Destination.Activity) emptyList()
    else running.map { it.entry }.filterNot { it in outputs && outputs[it] == null }

  val canStopCommand: Boolean get() = selectedRunning.let { it != null && it.stopping == null } && inFlight == null && !dialogOpen
  val canAcknowledge: Boolean get() = selectedRunning == null && selectedEntry?.needsAttention == true && inFlight == null && !dialogOpen

  fun commandOf(running: RunningOperation): String = commandOf(running, outputs[running.entry])

  /** What [entry] has said so far, as just read; null stops its polling. Nothing for a command no longer running. */
  fun read(entry: ActivityEntryId, output: RunningCommand?): GuiState =
    if (running.none { it.entry == entry }) this else copy(outputs = outputs + (entry to output))

  val runtimeWords: String get() = when (val current = attachment) {
    Attachment.Starting -> "Starting"
    Attachment.Attaching -> "Attaching"
    is Attachment.Attached -> "Attached"
    is Attachment.Absent -> when (val reason = current.reason) {
      Reason.NotAnswering -> if (stopped) "Stopped" else "Not running"
      is Reason.StartFailed -> reason.words
    }
  }

  /**
   * The next state. A new Runtime start clears everything that belonged to the old one; a stream
   * that ends takes its dialogs with it. Where the user is looking survives both.
   */
  fun observed(next: Attachment): GuiState {
    val newStart = next is Attachment.Attached && snapshot?.start?.id != next.snapshot.start?.id
    return if (newStart) GuiState(attachment = next, inFlight = inFlight, destination = destination, stage = stage)
    else {
      val attached = next is Attachment.Attached
      val stillThere = next is Attachment.Attached && next.snapshot.workspaces.any { it.workspace.id == workspace }
      val stillRunning = (next as? Attachment.Attached)?.snapshot?.running.orEmpty().mapTo(HashSet()) { it.entry }
      // A command that ended under its open Stop confirmation is not confirmed as though it ran.
      val ended = stopCommandConfirmation != null && attached && stopCommandConfirmation !in stillRunning
      copy(attachment = next, stopConfirmation = stopConfirmation && attached, adding = adding && attached,
        stopCommandConfirmation = stopCommandConfirmation.takeIf { it in stillRunning },
        outputs = outputs.filterKeys { it in stillRunning },
        noticeEntry = noticeEntry.takeIf { attached },
        notice = if (ended) Wording.ALREADY_ENDED else notice,
        registration = registration.takeIf { stillThere },
        trying = trying.takeIf { stillThere },
        workspace = workspace.takeIf { attached },
        activity = activity.takeIf { attached },
        commandConfirmation = commandConfirmation.takeIf { id ->
          next is Attachment.Attached && next.snapshot.workspaces.any { it.workspace.id == id }
        },
        refusal = refusal.takeIf { attached })
    }
  }

  /** What an intent that sends nothing to the Runtime does: navigation and opening dialogs. */
  fun after(intent: GuiIntent): GuiState = when (intent) {
    is GuiIntent.Show -> if (dialogOpen) this else copy(destination = intent.destination)
    is GuiIntent.ShowStage -> if (dialogOpen) this else copy(destination = Destination.Connection, stage = intent.stage)
    is GuiIntent.SelectWorkspace -> if (dialogOpen) this else copy(workspace = intent.id, destination = Destination.Workspaces)
    is GuiIntent.ShowActivity -> if (dialogOpen) this else copy(activity = intent.entry, destination = Destination.Activity)
    GuiIntent.Back -> when {
      dialogOpen -> this
      destination == Destination.Connection && stage != null -> copy(stage = null)
      destination == Destination.Workspaces && workspace != null -> copy(workspace = null)
      destination == Destination.Activity && activity != null -> copy(activity = null)
      else -> this
    }
    GuiIntent.AskStop -> if (canStop && !dialogOpen) copy(stopConfirmation = true, refusal = null) else this
    GuiIntent.CancelStop -> copy(stopConfirmation = false, refusal = null)
    GuiIntent.AddWorkspace -> if (canAdd && !dialogOpen) copy(adding = true, destination = Destination.Workspaces, refusal = null) else this
    GuiIntent.AskForget -> if (canActOnWorkspace) copy(registration = Registration.Forget, refusal = null) else this
    GuiIntent.AskReconfirm -> if (canActOnWorkspace && selectedWorkspace?.broken == true) copy(registration = Registration.Reconfirm, refusal = null) else this
    GuiIntent.AskMove -> if (canActOnWorkspace) copy(registration = Registration.Move, refusal = null) else this
    GuiIntent.AskRename -> if (canActOnWorkspace) copy(registration = Registration.Rename, refusal = null) else this
    GuiIntent.CancelRegistration -> copy(registration = null, refusal = null)
    GuiIntent.CancelAdd -> copy(adding = false, refusal = null)
    is GuiIntent.SetLevel -> if (canActOnWorkspace && intent.level == AccessLevel.Command && selectedWorkspace?.workspace?.accessLevel != AccessLevel.Command)
      copy(commandConfirmation = workspace, refusal = null) else this
    GuiIntent.CancelCommand -> copy(commandConfirmation = null, refusal = null)
    is GuiIntent.AskTry -> if (canActOnWorkspace) copy(trying = Trying(intent.tool), refusal = null) else this
    GuiIntent.CancelTry -> copy(trying = null, refusal = null)
    GuiIntent.ShowTried -> triedEntry?.let { copy(trying = null, refusal = null, destination = Destination.Activity, activity = it) } ?: this
    GuiIntent.AskStopCommand -> if (canStopCommand) copy(stopCommandConfirmation = activity, refusal = null) else this
    GuiIntent.CancelStopCommand -> copy(stopCommandConfirmation = null, refusal = null)
    GuiIntent.DismissNotice -> copy(notice = null, noticeEntry = null)
    GuiIntent.ConfirmStopCommand, GuiIntent.Acknowledge,
    GuiIntent.StartRuntime, GuiIntent.ConfirmStop, GuiIntent.ConnectTunnel, GuiIntent.DisconnectTunnel,
    GuiIntent.AcknowledgeConnector, GuiIntent.ConfirmCommand, is GuiIntent.Register, is GuiIntent.Rename, is GuiIntent.Move, GuiIntent.ConfirmReconfirm, GuiIntent.ConfirmForget,
    is GuiIntent.Try -> this
  }

  fun starting(): GuiState = GuiState(attachment = Attachment.Starting, destination = destination, stage = stage)

  /** One act in flight per window (GUI-SPEC §3.3); everything else stays live. */
  fun performing(act: ManagementAct<*>): GuiState =
    copy(inFlight = act, notice = null, noticeEntry = null, refusal = null, stopConfirmation = false,
      stopCommandConfirmation = stopCommandConfirmation.takeUnless { act is ManagementAct.StopOperation },
      trying = if (act is ManagementAct.TryOperation<*>) trying?.copy(result = null) else trying)

  /** A try's answer lands in its form if that is still open, and in the snackbar otherwise. */
  fun tried(result: Tried): GuiState =
    copy(inFlight = null, trying = trying?.copy(result = result), notice = result.words.takeIf { trying == null })

  /**
   * [words] null is success. A refusal lands in the dialog that asked, if it is still open, and in
   * the snackbar otherwise. A Stop that succeeded is what makes the Absent that follows "Stopped".
   */
  fun performed(act: ManagementAct<*>, words: String?): GuiState {
    val inDialog = (act is ManagementAct.Forget && registration == Registration.Forget) ||
      (act is ManagementAct.Reconfirm && (registration == Registration.Move || registration == Registration.Reconfirm)) ||
      (act is ManagementAct.Rename && registration == Registration.Rename) ||
      (act is ManagementAct.Register && adding) ||
      (act is ManagementAct.SetLevel && commandConfirmation == act.id) ||
      (act is ManagementAct.TryOperation<*> && trying != null)
    return copy(
      inFlight = null,
      registration = registration.takeUnless { (act is ManagementAct.Rename || act is ManagementAct.Reconfirm || act is ManagementAct.Forget) && words == null },
      adding = adding && !(act is ManagementAct.Register && words == null),
      commandConfirmation = commandConfirmation.takeUnless { act is ManagementAct.SetLevel && words == null && it == act.id },
      refusal = words.takeIf { inDialog },
      notice = if (act == ManagementAct.AcknowledgeConnector && words == null) Wording.CONNECTOR_ACKNOWLEDGED
        else words.takeUnless { inDialog },
      stopped = stopped || (act == ManagementAct.Stop && words == null),
    )
  }
}
