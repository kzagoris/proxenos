package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.NotSent
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.Outcome
import io.github.kzagoris.proxenos.coreapi.WorkspaceId
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.absoluteRoot
import io.github.kzagoris.proxenos.frontend.operationFrom
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

sealed interface GuiIntent {
  data class Show(val destination: Destination) : GuiIntent
  data class ShowStage(val stage: Stage) : GuiIntent
  data class SelectWorkspace(val id: WorkspaceId) : GuiIntent
  data class ShowActivity(val entry: ActivityEntryId) : GuiIntent
  data class SetLevel(val level: AccessLevel) : GuiIntent
  data object CancelCommand : GuiIntent
  data object ConfirmCommand : GuiIntent
  /** Esc's last layer: out of the compact detail. Dialogs handle their own Esc. */
  data object Back : GuiIntent
  data object StartRuntime : GuiIntent
  data object AskStop : GuiIntent
  data object CancelStop : GuiIntent
  data object ConfirmStop : GuiIntent
  data object ConnectTunnel : GuiIntent
  data object DisconnectTunnel : GuiIntent
  data object AcknowledgeConnector : GuiIntent
  data object AddWorkspace : GuiIntent
  data object CancelAdd : GuiIntent
  data object AskRename : GuiIntent
  data object AskForget : GuiIntent
  data object ConfirmForget : GuiIntent
  data object AskReconfirm : GuiIntent
  data object ConfirmReconfirm : GuiIntent
  data object AskMove : GuiIntent
  data class Move(val root: String) : GuiIntent
  data object CancelRegistration : GuiIntent
  data class Rename(val name: String) : GuiIntent
  /** [root] as typed; it is made absolute here, never in the Runtime's working directory. */
  data class Register(val root: String, val name: String?) : GuiIntent
  data class AskTry(val tool: String) : GuiIntent
  /** What was typed for each argument the open Try form asks; an empty one is not given. */
  data class Try(val given: Map<String, String>) : GuiIntent
  data object CancelTry : GuiIntent
  data object ShowTried : GuiIntent
  data object DismissNotice : GuiIntent
  /** Stop command… on the selected running command: the confirmation opens. */
  data object AskStopCommand : GuiIntent
  data object CancelStopCommand : GuiIntent
  data object ConfirmStopCommand : GuiIntent
  /** The selected entry's unresolved outcome has been seen. */
  data object Acknowledge : GuiIntent
}

/** What this window's `request_id`s start with: a TryOperation from here is a `gui-` one. */
private const val CALLER = "gui"

/** How often a running command's output is read while Activity shows (GUI-SPEC §3.5). */
private val OUTPUT_READ_INTERVAL = 1.seconds

private val Outcome<*>.tone: Tone get() = when (this) {
  is Outcome.Ok -> Tone.Ok
  is Outcome.Failed -> Tone.Warn
  is Outcome.Uncertain -> Tone.Bad
}

/** GUI-SPEC §3: state changes and intake share one dispatcher, independently of painting. */
class GuiOwner(
  private val attachment: RuntimeAttachment,
  private val management: WorkspaceManagement = attachment.management,
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
  private val current = MutableStateFlow(GuiState())
  val state = current.asStateFlow()
  private var opened = false

  /** Start intake after the window has drawn; a renderer failure must not start exposure. */
  fun open() {
    scope.launch {
      if (!opened) {
        opened = true
        scope.launch { poll() }
        attach()
      }
    }
  }

  fun accept(intent: GuiIntent) {
    scope.launch {
      val state = current.value
      when (intent) {
        GuiIntent.StartRuntime -> if (state.canStart) {
          current.value = state.starting()
          attach()
        }
        GuiIntent.ConfirmStop -> if (state.stopConfirmation && state.canStop) perform(ManagementAct.Stop)
        GuiIntent.ConnectTunnel -> if (state.canConnect) perform(ManagementAct.Connect)
        GuiIntent.DisconnectTunnel -> if (state.canDisconnect) perform(ManagementAct.Disconnect)
        GuiIntent.AcknowledgeConnector -> if (state.canAcknowledgeConnector) perform(ManagementAct.AcknowledgeConnector)
        is GuiIntent.SetLevel -> if (state.canActOnWorkspace) {
          val workspace = state.selectedWorkspace!!.workspace
          if (intent.level == AccessLevel.Command) current.value = state.after(intent)
          else if (intent.level != workspace.accessLevel) perform(ManagementAct.SetLevel(workspace.id, intent.level))
        }
        GuiIntent.ConfirmCommand -> if (state.commandConfirmation != null && state.inFlight == null) {
          perform(ManagementAct.SetLevel(state.commandConfirmation, AccessLevel.Command))
        }
        GuiIntent.ConfirmForget -> if (state.registration == Registration.Forget && state.inFlight == null)
          state.selectedWorkspace?.let { perform(ManagementAct.Forget(it.workspace.id)) }
        GuiIntent.ConfirmReconfirm -> if (state.registration == Registration.Reconfirm && state.inFlight == null)
          state.selectedWorkspace?.let { perform(ManagementAct.Reconfirm(it.workspace.id)) }
        is GuiIntent.Move -> if (state.registration == Registration.Move && state.inFlight == null && intent.root.isNotBlank())
          state.selectedWorkspace?.let { perform(ManagementAct.Reconfirm(it.workspace.id, absoluteRoot(intent.root).toString())) }
        is GuiIntent.Rename -> if (state.registration == Registration.Rename && state.inFlight == null && intent.name.isNotBlank())
          state.selectedWorkspace?.let { perform(ManagementAct.Rename(it.workspace.id, intent.name.trim())) }
        is GuiIntent.Register -> if (state.adding && state.inFlight == null && intent.root.isNotBlank())
          perform(ManagementAct.Register(absoluteRoot(intent.root).toString(), intent.name?.trim()?.ifBlank { null }))
        GuiIntent.ConfirmStopCommand -> state.stopCommandTarget?.takeIf { it.stopping == null && state.inFlight == null }?.let { running ->
            val command = state.commandOf(running)
            val act = ManagementAct.StopOperation(running.entry)
            // It returns once the command is reaped; the phases come from the stream meanwhile.
            perform(act) { performed(act, null).copy(notice = Wording.stopped(command), noticeEntry = running.entry) }
          }
        GuiIntent.Acknowledge -> if (state.canAcknowledge) perform(ManagementAct.Acknowledge(state.selectedEntry!!.id))
        is GuiIntent.Try -> if (state.trying != null && state.inFlight == null)
          state.selectedWorkspace?.let { tryOperation(state.trying.tool, it.workspace.name, intent.given) }
        else -> current.value = state.after(intent)
      }
    }
  }

  private suspend fun attach() {
    attachment.open().catch { failed ->
      emit(Attachment.Absent(Reason.StartFailed(failed.message ?: "Could not attach to the Runtime.")))
    }.collect { next ->
      current.value = current.value.observed(next)
    }
  }

  /**
   * What each running command has printed, asked for rather than streamed, so a window nobody has
   * on Activity costs the Runtime nothing. Not an act: it shows no progress and blocks nothing.
   * A command stops being polled when it ends or a read comes back null, and there is no final
   * read — its entry carries the recorded detail.
   */
  private suspend fun poll() {
    while (true) {
      delay(OUTPUT_READ_INTERVAL)
      val state = current.value
      for (running in state.running) {
        if (running.entry !in state.polled) continue
        // OperationStarted is published before the command is spawned, and a read in that gap is
        // null, which would end its polling for good: a command is first read once it has run an
        // interval. A spawn slower than that still loses its polling; the stream still ends it.
        if (Duration.between(running.startedAt, Instant.now()) < OUTPUT_READ_INTERVAL.toJavaDuration()) continue
        val output = try {
          management.perform(ManagementAct.ReadOutput(running.entry))
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          // A transport failure is not a null read; the stream's end says what happened.
          continue
        }
        current.value = current.value.read(running.entry, output)
      }
    }
  }

  /**
   * A frontend is an adapter like the `mcp` one: it builds the Operation or refuses to, and what
   * the Operation then does, every Access Level refusal included, is the pipeline's to say.
   */
  private suspend fun tryOperation(tool: String, workspace: String, given: Map<String, String>) {
    val op = try {
      operationFrom(CALLER, tool, workspace, given)
    } catch (refused: IllegalArgumentException) {
      current.value = current.value.copy(refusal = "${refused.message} Nothing was tried.")
      return
    }
    val before = current.value.snapshot?.activity.orEmpty().mapTo(HashSet()) { it.id }
    perform(ManagementAct.TryOperation(op)) { outcome ->
      tried(Tried(Wording.tried(tool, workspace, outcome, promotedLocation = "it is under Running now in Activity"),
        outcome.tone, workspace, (op as? Operation.Scoped<*>)?.deliveryKey, before))
    }
  }

  /** The stream decides what the screen shows; the act's result only says how it went. Never retried. */
  private suspend fun <R> perform(act: ManagementAct<R>, done: GuiState.(R) -> GuiState = { performed(act, null) }) {
    current.value = current.value.performing(act)
    val words = try {
      val result = management.perform(act)
      current.value = current.value.done(result)
      return
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: NotSent) {
      "The Runtime isn't answering; nothing was done."
    } catch (_: IOException) {
      "No reply; nothing was resent; what the Runtime shows now is what happened."
    } catch (refused: IllegalArgumentException) {
      refused.message ?: "The Runtime refused it."
    } catch (refused: IllegalStateException) {
      refused.message ?: "The Runtime refused it."
    }
    current.value = current.value.performed(act, words)
  }

  // Window closure never waits for an act or changes anything in the Runtime.
  override fun close() { scope.cancel() }
}
