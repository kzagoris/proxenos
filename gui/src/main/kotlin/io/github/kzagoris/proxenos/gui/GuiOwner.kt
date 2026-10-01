package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.NotSent
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import io.github.kzagoris.proxenos.frontend.absoluteRoot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed interface GuiIntent {
  data class Show(val destination: Destination) : GuiIntent
  data class ShowStage(val stage: Stage) : GuiIntent
  /** Esc's last layer: out of the compact detail. Dialogs handle their own Esc. */
  data object Back : GuiIntent
  data object StartRuntime : GuiIntent
  data object AskStop : GuiIntent
  data object CancelStop : GuiIntent
  data object ConfirmStop : GuiIntent
  data object AddWorkspace : GuiIntent
  data object CancelAdd : GuiIntent
  /** [root] as typed; it is made absolute here, never in the Runtime's working directory. */
  data class Register(val root: String, val name: String?) : GuiIntent
  data object DismissNotice : GuiIntent
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
        is GuiIntent.Register -> if (state.adding && state.inFlight == null && intent.root.isNotBlank())
          perform(ManagementAct.Register(absoluteRoot(intent.root).toString(), intent.name?.trim()?.ifBlank { null }))
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

  /** The stream decides what the screen shows; the act's result only says how it went. Never retried. */
  private suspend fun perform(act: ManagementAct<*>) {
    current.value = current.value.performing(act)
    val words = try {
      management.perform(act)
      null
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
