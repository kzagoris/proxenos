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
import kotlinx.serialization.SerializationException

/**
 * The control socket could not be dialed, so nothing reached the Runtime: the act was not done.
 * With [ReplyLost] it is the split ADR 0001 makes for an Operation, between a hard guarantee
 * that nothing happened and effects that are unknown.
 */
class NotSent(message: String, cause: Throwable) : IOException(message, cause)

/**
 * The connection was made and the request went, or may have gone, and no reply came: the act may
 * or may not have been done, and only the Runtime's state now says which.
 */
class ReplyLost(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [WorkspaceManagement] over the control socket: the design's one real seam. A
 * frontend holds this as the interface and cannot tell it from the core — every act is
 * serialize, send, deserialize, so there is no per-act method here to drift from the core.
 *
 * It holds no connection between calls. Each act and each observer dials its own, so any
 * number of frontends can attach at once and none of them holds anything another needs.
 */
class ManagementClient(private val socket: Path) : WorkspaceManagement {
  /**
   * Nothing is ever sent twice: a failure after the dial is [ReplyLost], and a retry could do
   * the act a second time.
   */
  override suspend fun <R> perform(act: ManagementAct<R>): R = blocking {
    dial().use { channel ->
      val frames = Frames(channel)
      val reply = replyLost {
        frames.sendOrHearRefusal(Request.Perform(wire.encodeToJsonElement(actSerializer, act)))
        frames.reply()
      }
      when (reply) {
        is Reply.Done -> wire.decodeFromJsonElement(resultSerializer(act), reply.result)
        is Reply.Refused -> throw refusal(reply)
        is Reply.Event -> throw IOException("The Runtime answered an act with an event")
        // Stop ends the process that would have answered, and may take the socket with it
        // before the answer is written. For this one act, the socket closing *is* the answer.
        @Suppress("UNCHECKED_CAST")
        null -> if (act == ManagementAct.Stop) Unit as R
        else throw ReplyLost("The Runtime closed the control socket without answering")
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

  private fun dial(): SocketChannel {
    var channel: SocketChannel? = null
    try {
      channel = SocketChannel.open(StandardProtocolFamily.UNIX)
      channel.connect(UnixDomainSocketAddress.of(socket))
      return channel
    } catch (failed: IOException) {
      channel?.close()
      if (failed is ClosedByInterruptException) throw failed
      throw NotSent("The Runtime is not answering on $socket", failed)
    }
  }

  /**
   * A failed write counts as lost rather than unsent: part of the request may have gone, and the
   * Runtime reads a last unterminated line as a request all the same. So does a reply that will
   * not decode: a Runtime that died mid-reply leaves the part it wrote as that last line. The
   * interrupt of a cancelled caller is not a transport failure, and is left for [blocking] to
   * turn back into one.
   */
  private inline fun <T> replyLost(exchange: () -> T): T = try {
    exchange()
  } catch (interrupted: ClosedByInterruptException) {
    throw interrupted
  } catch (failed: IOException) {
    throw ReplyLost("No reply from the Runtime on $socket", failed)
  } catch (garbled: SerializationException) {
    throw ReplyLost("No whole reply from the Runtime on $socket", garbled)
  }

  /** What the core itself would have thrown for the same act, as near as the wire allows. */
  private fun refusal(reply: Reply.Refused): RuntimeException =
    if (reply.invalidArgument) IllegalArgumentException(reply.reason) else IllegalStateException(reply.reason)
}
