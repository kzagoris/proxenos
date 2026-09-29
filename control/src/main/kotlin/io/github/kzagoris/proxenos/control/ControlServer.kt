package io.github.kzagoris.proxenos.control

import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * The Runtime's end of the control socket (SPEC §9): a [WorkspaceManagement] served to whoever
 * dials [socket] — provided the kernel says they are [owner].
 *
 * **Peer credentials, not file permissions, are the authentication.** The socket is created
 * owner-only in an owner-only directory, and that is a second fence; the first is `SO_PEERCRED`,
 * the uid the kernel attests for the process on the other end. A frontend cannot claim it.
 * A future Windows port loses this (SPEC §12): Windows AF_UNIX carries no ancillary data.
 *
 * It is a separate socket from MCP's on purpose. Management acts served on the catalog ChatGPT
 * enumerates would make raising an Access Level something a conversation could attempt; here
 * they are not on any path a conversation has.
 */
class ControlServer(
  private val management: WorkspaceManagement,
  private val socket: Path,
  /** The only Linux user served. The Runtime's own, unless a test says otherwise. */
  private val owner: String = System.getProperty("user.name"),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

  fun start() {
    // A single-instance lock is held before this is reached, so a file here is a dead
    // Runtime's leftover and not another's live socket.
    Files.deleteIfExists(socket)
    server.bind(UnixDomainSocketAddress.of(socket))
    Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
    scope.launch {
      while (isActive) {
        val channel = try {
          runInterruptible { server.accept() }
        } catch (_: IOException) {
          break // closed by stop()
        }
        launch { channel.use { serve(it) } }
      }
    }
  }

  fun stop() {
    scope.cancel()
    server.close()
    Files.deleteIfExists(socket)
  }

  private suspend fun serve(channel: SocketChannel) {
    val frames = Frames(channel)
    try {
      val peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user().name
      if (peer != owner) return frames.send(Reply.Refused("The control socket serves $owner only, not $peer."))
      when (val request = runInterruptible { frames.request() }) {
        null -> Unit
        is Request.Perform -> frames.send(perform(request))
        Request.Observe -> observe(channel, frames)
      }
    } catch (_: IOException) {
      // The frontend went away mid-answer. Its act, if any, was already done or refused.
    }
  }

  private suspend fun perform(request: Request.Perform): Reply = try {
    val act = wire.decodeFromJsonElement(actSerializer, request.act)
    @Suppress("UNCHECKED_CAST")
    val result = management.perform(act as ManagementAct<Any?>)
    Reply.Done(wire.encodeToJsonElement(resultSerializer(act), result))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (refused: IllegalArgumentException) {
    // The core's own `require`s — a name taken, an id that names nothing — and a frame that
    // does not decode, which is the same fact from the wire's side.
    Reply.Refused(refused.message ?: refused.toString(), invalidArgument = true)
  } catch (failed: Exception) {
    Reply.Refused(failed.message ?: failed.toString())
  }

  /**
   * Streams until either end goes. The read below only ever sees the frontend close, which is
   * how an observer that went away is noticed while nothing is changing — rather than at the
   * next change, which may be hours off.
   */
  private suspend fun observe(channel: SocketChannel, frames: Frames) = coroutineScope {
    val streaming = launch { management.observe().collect { frames.send(Reply.Event(it)) } }
    launch {
      runCatching { runInterruptible { frames.request() } }
      streaming.cancel()
    }.also { streaming.join(); it.cancel() }
    Unit
  }
}
