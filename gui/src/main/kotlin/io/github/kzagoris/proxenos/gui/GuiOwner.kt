package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.NotSent
import io.github.kzagoris.proxenos.control.ReplyLost
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
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
  data object StartRuntime : GuiIntent
  data object AskStop : GuiIntent
  data object CancelStop : GuiIntent
  data object ConfirmStop : GuiIntent
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
  private var attempt = 0

  init { scope.launch { attach() } }

  fun accept(intent: GuiIntent) {
    scope.launch {
      when (intent) {
        GuiIntent.StartRuntime -> if (current.value.attachment is Attachment.Absent && current.value.inFlight == null) {
          attempt++
          current.value = current.value.starting()
          attach()
        }
        GuiIntent.AskStop -> current.value = current.value.confirmStop()
        GuiIntent.CancelStop -> current.value = current.value.copy(stopConfirmation = false)
        GuiIntent.ConfirmStop -> if (current.value.stopConfirmation && current.value.snapshot != null && current.value.inFlight == null) stop()
        GuiIntent.DismissNotice -> current.value = current.value.copy(notice = null)
      }
    }
  }

  private suspend fun attach() {
    attachment.open().catch { failed ->
      emit(Attachment.Absent(Reason.StartFailed(failed.message ?: "Could not attach to the Runtime.")))
    }.collect { next ->
      if (next is Attachment.Attached && current.value.snapshot?.start?.id != next.snapshot.start?.id) attempt++
      current.value = current.value.observed(next)
    }
  }

  private suspend fun stop() {
    val startedAt = attempt
    current.value = current.value.stopping()
    val words = try {
      management.perform(ManagementAct.Stop)
      null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: NotSent) {
      "The Runtime isn't answering; nothing was done."
    } catch (_: ReplyLost) {
      "No reply; nothing was resent; what the Runtime shows now is what happened."
    } catch (_: IOException) {
      "No reply; nothing was resent; what the Runtime shows now is what happened."
    } catch (refused: IllegalArgumentException) {
      refused.message ?: "The Runtime refused Stop."
    } catch (refused: IllegalStateException) {
      refused.message ?: "The Runtime refused Stop."
    }
    if (attempt == startedAt) current.value = current.value.finishedStop(words)
  }

  // Window closure never waits for an act or changes anything in the Runtime.
  override fun close() { scope.cancel() }
}
