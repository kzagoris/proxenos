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
import kotlinx.coroutines.withContext
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
  data object OpenSetup : GuiIntent
  data object FinishLater : GuiIntent
  data object CheckSetup : GuiIntent
  data class SetupNavigate(val step: SetupStep, val tunnelPart: Int = 0, val tunnelId: String? = null) : GuiIntent
  data class SetupAssociated(val associated: Boolean) : GuiIntent
  data class SetupChoose(val id: WorkspaceId) : GuiIntent
  // A plain class deliberately has no generated toString carrying the key.
  class SaveCredentials(val tunnelId: String, val runtimeKey: String) : GuiIntent
  data object SetupConfirmPlugin : GuiIntent
  data object CompleteSetup : GuiIntent
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
  private val setupFiles: SetupFiles? = null,
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
  private val current = MutableStateFlow(GuiState())
  val state = current.asStateFlow()
  private var opened = false
  private var awaitingPluginConfirmation = false
  private var connectAfterCredentialStart = false

  /** Start intake after the window has drawn; a renderer failure must not start exposure. */
  fun open() {
    scope.launch {
      if (!opened) {
        opened = true
        setupFiles?.let { files ->
          try {
            val progress = withContext(Dispatchers.IO) { files.loadProgress() }
            val savedId = withContext(Dispatchers.IO) { files.tunnelId() }
            val tunnelId = savedId ?: progress.tunnelId
            current.value = current.value.copy(setup = progress, setupTunnelId = tunnelId, setupHasCredentials = savedId != null,
              setupVisible = savedId == null || (!progress.completed && progress.step != SetupStep.Welcome))
            checkSetup()
          } catch (_: Exception) {
            current.value = current.value.copy(setup = SetupProgress(), setupVisible = true,
              setupError = "Could not read local setup files. Check your Proxenos configuration directory.")
          }
        }
        scope.launch { poll() }
        // Credential entry is part of onboarding; attach to an existing Runtime, but do not
        // launch an absent one just to receive its terminal-wizard diagnostic (ADR 0012).
        attach(startIfAbsent = setupFiles == null || current.value.setupHasCredentials)
      }
    }
  }

  fun accept(intent: GuiIntent) {
    scope.launch {
      val state = current.value
      when (intent) {
        GuiIntent.CheckSetup -> checkSetup()
        is GuiIntent.SetupAssociated -> state.setup?.let { changeSetup(it.copy(associated = intent.associated)) }
        is GuiIntent.SetupChoose -> if (state.snapshot?.workspaces?.any { it.workspace.id == intent.id && !it.broken && it.workspace.accessLevel != AccessLevel.None } == true)
          state.setup?.let { changeSetup(it.copy(workspace = intent.id.value, waitingSince = null, completed = false)) }
        is GuiIntent.SetupNavigate -> navigateSetup(intent)
        is GuiIntent.SaveCredentials -> saveCredentials(intent)
        GuiIntent.SetupConfirmPlugin -> if (state.setup?.selected(state.snapshot)?.let { !it.broken && it.workspace.accessLevel != AccessLevel.None } == true && state.inFlight == null) {
          if (state.snapshot?.connectorUnconfirmed == true) {
            perform(ManagementAct.AcknowledgeConnector)
            awaitingPluginConfirmation = current.value.notice == Wording.CONNECTOR_ACKNOWLEDGED
          } else awaitingPluginConfirmation = state.snapshot != null
          advanceConfirmedPlugin()
        }
        GuiIntent.CompleteSetup -> state.setup?.takeIf { it.evidence(state.snapshot) != null }?.let {
          changeSetup(it.copy(completed = true))
          current.value = current.value.copy(setupVisible = false, destination = Destination.Workspaces)
        }
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
        is GuiIntent.Register -> if ((state.adding || (state.setupVisible && state.setup?.step == SetupStep.Workspace)) && state.inFlight == null && intent.root.isNotBlank())
          perform(ManagementAct.Register(absoluteRoot(intent.root).toString(), intent.name?.trim()?.ifBlank { null })) { workspace ->
            performed(ManagementAct.Register(absoluteRoot(intent.root).toString(), intent.name), null)
              .let { if (it.setupVisible) it.copy(setup = it.setup?.copy(workspace = workspace.id.value, waitingSince = null)) else it }
          }.also { current.value.setup?.let { changeSetup(it) } }
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

  private suspend fun advanceConfirmedPlugin() {
    if (awaitingPluginConfirmation && current.value.snapshot?.connectorUnconfirmed == false) {
      awaitingPluginConfirmation = false
      navigateSetup(GuiIntent.SetupNavigate(SetupStep.TryIt))
    }
  }

  private suspend fun checkSetup() {
    val files = setupFiles ?: return
    try {
      val checks = withContext(Dispatchers.IO) { files.prerequisites() }
      current.value = current.value.copy(setupChecks = checks)
    } catch (_: Exception) {
      current.value = current.value.copy(setupChecks = listOf("Could not check local requirements. Review config.toml and the installed launchers."))
    }
  }

  private fun changeSetup(progress: SetupProgress) {
    try {
      // This tiny atomic file write stays on the owner's background dispatcher. No suspension
      // separates saving and publishing, so closing after a completed step cannot lose progress.
      setupFiles?.saveProgress(progress)
      current.value = current.value.copy(setup = progress, setupError = null)
    } catch (_: IOException) {
      current.value = current.value.copy(setup = progress,
        setupError = "Setup progress could not be saved. Check your Proxenos configuration directory before closing.")
    }
  }

  private suspend fun navigateSetup(intent: GuiIntent.SetupNavigate) {
    val state = current.value
    val progress = state.setup ?: return
    if (state.setupBusy || state.dialogOpen) return
    val selected = progress.selected(state.snapshot)
    val ready = selected != null && !selected.broken && selected.workspace.accessLevel != AccessLevel.None
    val allowed = when (intent.step) {
      SetupStep.Welcome, SetupStep.Tunnel -> true
      SetupStep.Workspace -> state.snapshot?.runtime?.state == io.github.kzagoris.proxenos.coreapi.RuntimeState.Connected
      SetupStep.ChatGPT -> ready
      SetupStep.TryIt -> ready && state.snapshot?.connectorUnconfirmed == false
    }
    if (allowed) {
      intent.tunnelId?.let { current.value = current.value.copy(setupTunnelId = it.trim()) }
      changeSetup(progress.copy(tunnelId = current.value.setupTunnelId, furthest = maxOf(progress.furthest, progress.step, intent.step), step = intent.step, tunnelPart = intent.tunnelPart.coerceIn(0, 2),
      waitingSince = if (intent.step == SetupStep.TryIt) progress.waitingSince.takeIf {
        progress.waitingRoot == selected?.workspace?.root && progress.waitingName == selected?.workspace?.name
      } ?: Instant.now().toEpochMilli() else progress.waitingSince,
      waitingStart = if (intent.step == SetupStep.TryIt) state.snapshot?.start?.id?.value else progress.waitingStart,
      waitingRoot = if (intent.step == SetupStep.TryIt) selected?.workspace?.root else progress.waitingRoot,
      waitingName = if (intent.step == SetupStep.TryIt) selected?.workspace?.name else progress.waitingName))
    }
  }

  private suspend fun saveCredentials(intent: GuiIntent.SaveCredentials) {
    val files = setupFiles ?: return
    if (!current.value.canStart || !current.value.setupVisible) return
    current.value = current.value.copy(setupBusy = true, setupError = null)
    try {
      // A stream loss is not evidence the Runtime stopped. Never change credentials underneath it.
      check(!attachment.answering()) { "The Runtime is still answering. Stop it explicitly before saving new credentials." }
      withContext(Dispatchers.IO) { files.saveCredentials(intent.tunnelId, intent.runtimeKey) }
      current.value = current.value.copy(setupTunnelId = intent.tunnelId.trim(), setupHasCredentials = true, setupBusy = false)
      current.value.setup?.let { changeSetup(it.copy(step = SetupStep.Tunnel, tunnelPart = 2, waitingSince = null, completed = false)) }
      connectAfterCredentialStart = true
      current.value = current.value.starting()
      scope.launch { attach() }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (invalid: IllegalArgumentException) {
      current.value = current.value.copy(setupBusy = false, setupError = invalid.message)
    } catch (running: IllegalStateException) {
      current.value = current.value.copy(setupBusy = false, setupError = running.message)
    } catch (_: IOException) {
      current.value = current.value.copy(setupBusy = false, setupError = "Credentials could not be saved. Check your Proxenos configuration directory; no Runtime was started.")
    }
  }

  private suspend fun attach(startIfAbsent: Boolean = true) {
    attachment.attach(startIfAbsent).catch { failed ->
      emit(Attachment.Absent(Reason.StartFailed(failed.message ?: "Could not attach to the Runtime.")))
    }.collect { next ->
      val previous = current.value
      current.value = previous.observed(next)
      if (connectAfterCredentialStart && next is Attachment.Attached) {
        connectAfterCredentialStart = false
        if (current.value.canConnect) perform(ManagementAct.Connect)
      }
      val progress = current.value.setup
      val selected = progress?.selected(current.value.snapshot)
      if (progress?.step == SetupStep.TryIt && !progress.completed && next is Attachment.Attached &&
        (progress.waitingStart != next.snapshot.start?.id?.value ||
          progress.waitingRoot != selected?.workspace?.root || progress.waitingName != selected?.workspace?.name ||
          (previous.snapshot != null && previous.setup?.selected(previous.snapshot) != selected) || (progress.waitingSince == null &&
            selected != null && !selected.broken && selected.workspace.accessLevel != AccessLevel.None))) {
        val ready = selected != null && !selected.broken && selected.workspace.accessLevel != AccessLevel.None
        changeSetup(progress.copy(waitingStart = next.snapshot.start?.id?.value, waitingSince = if (ready) Instant.now().toEpochMilli() else null,
          waitingRoot = selected?.workspace?.root, waitingName = selected?.workspace?.name, completed = false))
      }
      advanceConfirmedPlugin()
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
