package io.github.kzagoris.proxenos.runtime

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * One Runtime at a time (SPEC §8.2). [forSocket] is the lock §8.2 names, on a file beside the
 * MCP socket rather than on the socket file itself, and it is taken **before** anything binds:
 * Ktor unlinks and rebinds over a socket file it finds, so a second Runtime that got
 * as far as binding would already have taken the first one's socket away from it.
 * [forStateDirectory] is the same lock on the registry and Activity, so a second Runtime pointed
 * at another socket still cannot keep an account the first one cannot see.
 *
 * The kernel drops the lock when the process ends however it ends, so a Runtime the machine
 * took leaves nothing behind that refuses the next one.
 */
internal class InstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
  override fun close() {
    lock.release()
    channel.close()
  }

  companion object {
    fun forSocket(socket: Path): InstanceLock = acquire(socket.resolveSibling("${socket.fileName}.lock"), "the lock on $socket")

    fun forStateDirectory(directory: Path): InstanceLock = acquire(directory.resolve("runtime.lock"), "the lock on $directory")

    private fun acquire(file: Path, what: String): InstanceLock {
      val channel = FileChannel.open(file, CREATE, WRITE)
      val lock = try {
        channel.tryLock()
      } catch (failure: Throwable) {
        channel.close()
        throw failure
      }
      if (lock == null) {
        channel.close()
        throw StartRefused(
          "A Runtime is already running: another process holds $file, $what. One " +
            "Runtime serves every Workspace, and any number of frontends can attach to it.",
        )
      }
      return InstanceLock(channel, lock)
    }
  }
}
