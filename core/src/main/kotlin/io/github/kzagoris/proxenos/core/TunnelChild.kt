package io.github.kzagoris.proxenos.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The official `tunnel-client`, run as this Runtime's child (SPEC §7). The Runtime owns it
 * rather than the other way round, so a tunnel that dies takes no registration and no running
 * Operation with it: it is reported, and started again.
 *
 * **Ownership of the child carries ownership of the credentials.** They are put into the
 * child's environment here and nowhere else — never into the Runtime's own, which it cannot
 * change anyway, and so never into a `run_command` child either.
 *
 * This restarts a child that **dies**, not a link that is lost: `tunnel-client` never gives up
 * and never exits on a bad key (§8.4), so a death is a crash or a kill, and it is answered by
 * starting it again after a delay that grows while the deaths keep coming. Nothing here reads
 * the link at all; that is [Tunnel]'s judgement, and it never restarts anything.
 *
 * A child is wanted or not: Disconnect ends it and starts no other until Connect (§8.1).
 */
internal class TunnelChild(
  private val executable: Path,
  private val arguments: List<String>,
  private val credentials: TunnelCredentials,
  /** How long the tree is given after TERM before it is killed (§6.3's grace). */
  private val grace: Duration,
  /** Where the child writes its health base URL. Removed before each launch (see [supervise]). */
  private val healthUrlFile: Path,
  private val log: (String) -> Unit,
  /** Each line the child writes, as it writes it. */
  private val heard: (String) -> Unit,
) {
  private val lock = ReentrantLock()
  private val changed = lock.newCondition()
  private var stopping = false
  private var wanted = false
  private var current: Process? = null
  /** The child Disconnect ended, so its exit is not mistaken for a death — even once Connect is back. */
  private var ended: Process? = null
  private val supervisor = Thread(::supervise, "tunnel-child")

  /** Begins supervising; a child is launched only if [connected]. */
  fun start(connected: Boolean) {
    lock.withLock { wanted = connected }
    supervisor.start()
  }

  /** Blocks until [stop] has ended supervision. */
  fun join() = supervisor.join()

  fun connect() = lock.withLock {
    wanted = true
    changed.signalAll()
  }

  /** Ends the child's tree, and launches no other until [connect]. Blocks for up to the grace. */
  fun disconnect() {
    val process = lock.withLock {
      wanted = false
      changed.signalAll()
      current?.also { ended = it }
    }
    process?.let(::endTree)
  }

  /**
   * Ends supervision and the child's whole tree, leaving **no orphans**. The tree is
   * listed while the child is still alive: once it dies its own children are re-parented and no
   * longer count as its descendants, so a list taken afterwards would miss exactly the processes
   * that need killing.
   */
  fun stop() {
    val process = lock.withLock {
      stopping = true
      changed.signalAll()
      current
    }
    process?.let(::endTree)
    supervisor.join(grace.inWholeMilliseconds + 1_000)
  }

  private fun supervise() {
    var delay = FIRST_RESTART_DELAY
    while (true) {
      val process = lock.withLock {
        while (!stopping && !wanted) changed.await()
        if (stopping) return
        // The previous child's URL is the previous child's: a file left behind would be read as
        // the rendezvous of one that has not bound anything yet.
        try {
          Files.deleteIfExists(healthUrlFile)
        } catch (failed: IOException) {
          log("could not remove $healthUrlFile before starting tunnel-client: ${failed.message}")
        }
        try {
          launch().also { current = it }
        } catch (failed: IOException) {
          log("tunnel-client could not be started from $executable: ${failed.message}")
          null
        }
      }
      val startedAt = TimeSource.Monotonic.markNow()
      if (process != null) {
        log("tunnel-client started, pid ${process.pid()}")
        drain(process)
        val code = process.waitFor()
        val disconnected = lock.withLock {
          current = null
          if (stopping) return
          (ended === process).also { ended = null }
        }
        if (disconnected) {
          log("tunnel-client (pid ${process.pid()}) ended by Disconnect.")
          delay = FIRST_RESTART_DELAY
          continue
        }
        // A child that ran a while before dying is a fresh failure, not the next of a series.
        if (startedAt.elapsedNow() > STABLE_RUN) delay = FIRST_RESTART_DELAY
        log("tunnel-client (pid ${process.pid()}) exited with code $code. The Runtime stays up and starts it again in $delay.")
      }
      lock.withLock {
        if (stopping || !wanted) return@withLock
        changed.await(delay.inWholeMilliseconds, MILLISECONDS)
      }
      delay = (delay * 2).coerceAtMost(LAST_RESTART_DELAY)
    }
  }

  private fun launch(): Process {
    val builder = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true)
    builder.environment().apply {
      // Whatever the Runtime inherited under these names is not the credential: only the file is.
      remove(TUNNEL_ID)
      remove(API_KEY)
      put(TUNNEL_ID, credentials.tunnelId)
      put(API_KEY, credentials.runtimeKey)
    }
    return builder.start()
  }

  /** Forwards the child's output, and keeps its pipe from filling — a child that cannot write stops. */
  private fun drain(process: Process) {
    Thread({
      try {
        process.inputStream.bufferedReader().forEachLine {
          // Taken in before it is logged, so a line the log shows is one the link state has heard.
          heard(it)
          log("[tunnel-client] $it")
        }
      } catch (_: IOException) {
        // The pipe closed under us as the child died, which the supervisor reports itself.
      }
    }, "tunnel-child-output").apply { isDaemon = true }.start()
  }

  /**
   * The tree alone, not the tree and the group as §6.3's one kill does for a command: the child
   * is started in the Runtime's own process group, so signalling that group would signal the
   * Runtime in the middle of its own shutdown. What the group arm exists to catch — a
   * descendant that detached with `setsid` — is not something `tunnel-client` does.
   */
  private fun endTree(process: Process) {
    val tree = process.descendants().toList() + process.toHandle()
    tree.forEach(ProcessHandle::destroy)
    val deadline = TimeSource.Monotonic.markNow() + grace
    while (tree.any(ProcessHandle::isAlive) && !deadline.hasPassedNow()) Thread.sleep(POLL.inWholeMilliseconds)
    val survivors = tree.filter(ProcessHandle::isAlive)
    survivors.forEach(ProcessHandle::destroyForcibly)
    if (survivors.isNotEmpty()) log("tunnel-client's tree outlived TERM by $grace; killed ${survivors.map(ProcessHandle::pid)}.")
  }

  companion object {
    /** The names `tunnel-client run` takes the credential under (SPEC §11.1). */
    const val TUNNEL_ID = "CONTROL_PLANE_TUNNEL_ID"
    const val API_KEY = "CONTROL_PLANE_API_KEY"

    private val FIRST_RESTART_DELAY = 1.seconds
    private val LAST_RESTART_DELAY = 30.seconds
    private val STABLE_RUN = 1.minutes
    private val POLL = 20.milliseconds
  }
}

/**
 * The tunnel ID and runtime key (SPEC §11.1). Held in memory and handed to the tunnel child's
 * environment, and to nothing else. [toString] never prints the key, so a stray log line or an
 * exception message cannot carry it out.
 */
class TunnelCredentials(val tunnelId: String, val runtimeKey: String) {
  override fun toString(): String = "TunnelCredentials(tunnelId=$tunnelId, runtimeKey=<not shown>)"
}
