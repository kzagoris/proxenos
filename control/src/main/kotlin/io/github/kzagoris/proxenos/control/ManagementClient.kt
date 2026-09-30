package io.github.kzagoris.proxenos.control

import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible

/**
 * [WorkspaceManagement] over the control socket: the design's one real seam. A
 * frontend holds this as the interface and cannot tell it from the core — every act is
 * serialize, send, deserialize, so there is no per-act method here to drift from the core.
 *
 * It holds no connection between calls. Each act and each observer dials its own, so any
 * number of frontends can attach at once and none of them holds anything another needs.
 */
class ManagementClient(private val socket: Path) : WorkspaceManagement {
  override suspend fun <R> perform(act: ManagementAct<R>): R = blocking {
    dial().use { channel ->
      val frames = Frames(channel)
      frames.sendOrHearRefusal(Request.Perform(wire.encodeToJsonElement(actSerializer, act)))
      when (val reply = frames.reply()) {
        is Reply.Done -> wire.decodeFromJsonElement(resultSerializer(act), reply.result)
        is Reply.Refused -> throw refusal(reply)
        is Reply.Event -> throw IOException("The Runtime answered an act with an event")
        // Stop ends the process that would have answered, and may take the socket with it
        // before the answer is written. For this one act, the socket closing *is* the answer.
        @Suppress("UNCHECKED_CAST")
        null -> if (act == ManagementAct.Stop) Unit as R
        else throw IOException("The Runtime closed the control socket without answering")
      }
    }
  }

  override fun observe(): Flow<RuntimeEvent> = flow {
    dial().use { channel ->
      val frames = Frames(channel)
      frames.sendOrHearRefusal(Request.Observe)
      while (true) {
        // A collector that is cancelled is not left blocked on this read: the interrupt closes
        // the channel, which is also how the Runtime learns this observer has gone.
        when (val reply = blocking { frames.reply() }) {
          is Reply.Event -> emit(reply.event)
          is Reply.Refused -> throw refusal(reply)
          is Reply.Done -> throw IOException("The Runtime answered an observer with a result")
          null -> throw IOException("The Runtime closed the control socket")
        }
      }
    }
  }.flowOn(Dispatchers.IO)

  /**
   * A blocking read or write that a cancelled caller is not left stuck in. The interrupt that
   * frees the thread closes the channel, and the JDK reports that as an I/O error — which here
   * is not one: it is the cancellation, and is rethrown as that.
   */
  private suspend fun <T> blocking(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
  } catch (interrupted: ClosedByInterruptException) {
    throw CancellationException("Cancelled while waiting on the control socket").apply { initCause(interrupted) }
  }

  /**
   * The Runtime refuses a stranger before reading anything, then closes. A request written after
   * that close fails with a broken pipe, yet the refusal is already waiting to be read, and it is
   * the real answer: the pipe breaking is only its consequence.
   */
  private fun Frames.sendOrHearRefusal(request: Request) {
    try {
      send(request)
    } catch (broken: IOException) {
      val reply = runCatching { reply() }.getOrNull()
      if (reply is Reply.Refused) throw refusal(reply)
      throw broken
    }
  }

  private fun dial(): SocketChannel =
    SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socket)) }

  /** What the core itself would have thrown for the same act, as near as the wire allows. */
  private fun refusal(reply: Reply.Refused): RuntimeException =
    if (reply.invalidArgument) IllegalArgumentException(reply.reason) else IllegalStateException(reply.reason)
}
