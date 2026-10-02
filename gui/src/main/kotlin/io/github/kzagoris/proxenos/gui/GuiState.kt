package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.WorkspaceId
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording

/** The sidebar's three places, in Ctrl+1/2/3 order (GUI-SPEC §4.1). */
enum class Destination { Workspaces, Activity, Connection }

/** The chain a ChatGPT call travels, each stage measured on its own (ADR 0009). */
enum class Stage { Runtime, Tunnel, Connector }

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
  /** Kept when forgotten, so the detail says gone instead of selecting a neighbour. */
  val workspace: WorkspaceId? = null,
  /** The running command the Workspaces banner opens; Activity detail is built in its own ticket. */
  val activity: ActivityEntryId? = null,
  val commandConfirmation: WorkspaceId? = null,
) {
  val snapshot: RuntimeEvent.Snapshot? get() = (attachment as? Attachment.Attached)?.snapshot
  val canStart: Boolean get() = attachment is Attachment.Absent && inFlight == null
  val canStop: Boolean get() = snapshot != null && inFlight == null
  val canAdd: Boolean get() = snapshot != null && inFlight == null
  val canConnect: Boolean get() = snapshot?.runtime?.state == RuntimeState.Disconnected && inFlight == null && !dialogOpen
  val canDisconnect: Boolean get() = snapshot != null && snapshot?.runtime?.state != RuntimeState.Disconnected && inFlight == null && !dialogOpen
  val canAcknowledgeConnector: Boolean get() = snapshot?.connectorUnconfirmed == true && inFlight == null && !dialogOpen
  val selectedWorkspace: WorkspaceState? get() = snapshot?.workspaces?.find { it.workspace.id == workspace }
  val canSetLevel: Boolean get() = selectedWorkspace != null && inFlight == null && !dialogOpen
  val dialogOpen: Boolean get() = stopConfirmation || adding || commandConfirmation != null

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
      copy(attachment = next, stopConfirmation = stopConfirmation && attached, adding = adding && attached,
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
      else -> this
    }
    GuiIntent.AskStop -> if (canStop && !dialogOpen) copy(stopConfirmation = true, refusal = null) else this
    GuiIntent.CancelStop -> copy(stopConfirmation = false, refusal = null)
    GuiIntent.AddWorkspace -> if (canAdd && !dialogOpen) copy(adding = true, destination = Destination.Workspaces, refusal = null) else this
    GuiIntent.CancelAdd -> copy(adding = false, refusal = null)
    is GuiIntent.SetLevel -> if (canSetLevel && intent.level == AccessLevel.Command && selectedWorkspace?.workspace?.accessLevel != AccessLevel.Command)
      copy(commandConfirmation = workspace, refusal = null) else this
    GuiIntent.CancelCommand -> copy(commandConfirmation = null, refusal = null)
    GuiIntent.DismissNotice -> copy(notice = null)
    GuiIntent.StartRuntime, GuiIntent.ConfirmStop, GuiIntent.ConnectTunnel, GuiIntent.DisconnectTunnel,
    GuiIntent.AcknowledgeConnector, GuiIntent.ConfirmCommand, is GuiIntent.Register -> this
  }

  fun starting(): GuiState = GuiState(attachment = Attachment.Starting, destination = destination, stage = stage)

  /** One act in flight per window (GUI-SPEC §3.3); everything else stays live. */
  fun performing(act: ManagementAct<*>): GuiState =
    copy(inFlight = act, notice = null, refusal = null, stopConfirmation = false)

  /**
   * [words] null is success. A refusal lands in the dialog that asked, if it is still open, and in
   * the snackbar otherwise. A Stop that succeeded is what makes the Absent that follows "Stopped".
   */
  fun performed(act: ManagementAct<*>, words: String?): GuiState {
    val inDialog = (act is ManagementAct.Register && adding) ||
      (act is ManagementAct.SetLevel && commandConfirmation == act.id)
    return copy(
      inFlight = null,
      adding = adding && !(act is ManagementAct.Register && words == null),
      commandConfirmation = commandConfirmation.takeUnless { act is ManagementAct.SetLevel && words == null && it == act.id },
      refusal = words.takeIf { inDialog },
      notice = if (act == ManagementAct.AcknowledgeConnector && words == null) Wording.CONNECTOR_ACKNOWLEDGED
        else words.takeUnless { inDialog },
      stopped = stopped || (act == ManagementAct.Stop && words == null),
    )
  }
}
