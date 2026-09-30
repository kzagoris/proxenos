package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.lang.ProcessBuilder.Redirect.DISCARD
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible

private const val SHELL = "/bin/sh"

/**
 * The command runs in a **session of its own**, which is the whole of what makes the group arm
 * of the kill usable: a child left in the Runtime's own process group could not be signalled by
 * group without signalling the Runtime with it.
 */
private const val NEW_SESSION_PROGRAM = "setsid"

/** Signalling a *group* is the one thing `ProcessHandle` cannot do, so it is done by `kill`. */
private const val SIGNAL_PROGRAM = "kill"

/**
 * The Runtime passes these to the tunnel child and to nothing else — it does not
 * put them in its own environment. Stripping them here is therefore defence in depth rather
 * than the only guard, which is what it should have been all along: without it, any command at
 * Command level could read the tunnel credentials out of its own environment and print them
 * into a conversation that leaves the machine.
 */
internal val TUNNEL_CREDENTIALS = listOf("CONTROL_PLANE_TUNNEL_ID", "CONTROL_PLANE_API_KEY")

/** How often a wait for a signal to land looks again. Short against a 5-second grace. */
private val POLL = 20.milliseconds

/** How long a KILL is given to land before what is still alive is called a survivor. */
private val KILL_SETTLE = 500.milliseconds

/** How long the drain is given after the child has gone, before its pipe is closed under it. */
private val DRAIN_SETTLE = 500.milliseconds

/**
 * How long the child is given to enter a session of its own. `setsid` execs before the command
 * it was handed runs at all, so this is microseconds in practice and a bound on a hang rather
 * than a delay anybody pays.
 */
private val SESSION_WAIT = 200.milliseconds

/** How long `kill` itself is waited for. It either signalled or it did not; it does not work. */
private const val SIGNAL_TIMEOUT_MS = 2_000L

/**
 * `run_command`, and the one kill mechanic on this machine.
 *
 * Two things here are decisions rather than details.
 *
 * The first is that **the Root does not confine what this runs**. A command at
 * [AccessLevel.Command] carries the full authority of the user's Linux account and can read
 * `~/.ssh` whatever confinement says; only [Operation.RunCommand.cwd] is confined, and it is confined
 * by the pipeline like any other path argument. Mandatory sandboxing was considered and
 * rejected by the user. Nothing here may quietly narrow that.
 *
 * The second is that there is **exactly one kill** ([reap]), because ChatGPT's Stop is
 * unreachable — measured: zero `notifications/cancelled` frames in an entire session, and a
 * call stopped eight seconds in ran its full 300 seconds. The frontend's stop control, the
 * budget above and Runtime Stop all reach this same mechanic, and no second one exists.
 *
 * [shell], [newSession], [signaller] and [grace] are configuration for the reason ADR 0002
 * gives: nothing below the core is abstracted, so a test drives a real child process with a
 * real stub script and sets a short grace, rather than mocking a `ProcessLauncher` that would
 * have exactly one implementation.
 */
internal class CommandRunner(
  private val shell: String = SHELL,
  private val newSession: String = NEW_SESSION_PROGRAM,
  private val signaller: String = SIGNAL_PROGRAM,
  private val grace: Duration = Operation.KILL_GRACE,
  /**
   * The Runtime's own environment, which every child inherits less [TUNNEL_CREDENTIALS]. Taken
   * rather than reached for, so that a test can hand in one holding the credentials and watch a
   * real child fail to find them — which is the only way this guard is a fact rather than a
   * line of code nobody has ever seen run.
   */
  private val environment: Map<String, String> = System.getenv(),
) {
  /**
   * Started in a session of its own where this machine has `setsid`, and plainly where it does
   * not. The fallback is not a detail: `setsid` is a dependency the design never asked for, and failing
   * every command on a machine without it would be a worse answer than running the command with
   * one arm of the kill missing — which [CommandExecution.reap] then says out loud by naming the
   * survivors it could not reach, rather than by claiming a group was signalled.
   */
  fun start(command: String, directory: Path): CommandExecution {
    val sessioned = try {
      builder(command, directory, ownSession = true).start()
    } catch (_: IOException) {
      null // No `setsid` on this machine.
    }
    if (sessioned != null) return CommandExecution(sessioned, signaller, grace, ownSession = true)
    return CommandExecution(
      builder(command, directory, ownSession = false).start(), signaller, grace, ownSession = false,
    )
  }

  private fun builder(command: String, directory: Path, ownSession: Boolean): ProcessBuilder {
    val program = if (ownSession) listOf(newSession, shell, "-c", command) else listOf(shell, "-c", command)
    val builder = ProcessBuilder(program)
      .directory(directory.toFile())
      // One stream, in the order the command wrote it: that is the account a command gives of
      // itself, and the cap bounds one buffer per Operation rather than two halves of it.
      .redirectErrorStream(true)
    // Inherited from the Runtime process, less the two that must never reach a child.
    val inherited = builder.environment()
    inherited.clear()
    inherited.putAll(environment)
    TUNNEL_CREDENTIALS.forEach { inherited.remove(it) }
    return builder
  }
}

/**
 * One command, running. Its output is drained on a thread of its own from the moment it starts:
 * a child whose pipe fills while nobody reads it stops writing, and a command whose output
 * nobody is taking is a command that never ends.
 */
internal class CommandExecution(
  private val process: Process,
  private val signaller: String,
  private val grace: Duration,
  /** False where this machine had no `setsid`, and the kill therefore has its tree arm alone. */
  ownSession: Boolean,
) {
  /** The group both arms of the kill need, observed once the child is in one of its own. */
  private val group: Long? = if (ownSession) observeGroup(process) else null

  private val captured = HeadAndTailBytes(Operation.OUTPUT_HEAD_BYTES, Operation.OUTPUT_TAIL_BYTES)

  private val draining = Thread.ofVirtual().start {
    try {
      val chunk = ByteArray(1 shl 16)
      while (true) {
        val read = process.inputStream.read(chunk)
        if (read < 0) break
        captured.accept(chunk, 0, read)
      }
    } catch (_: IOException) {
      // The pipe was closed under us, which is what ends a drain a grandchild was holding open.
    }
  }

  init {
    // A command reading standard input sees EOF rather than a pipe nobody will ever write to.
    try {
      process.outputStream.close()
    } catch (_: IOException) {
      // It had already gone. There is nothing to feed it either way.
    }
  }

  /** The exit code, or null when [budget] ran out first and the command is still running. */
  fun awaitExit(budget: Duration): Int? =
    if (process.waitFor(budget.inWholeMilliseconds, MILLISECONDS)) process.exitValue() else null

  /**
   * The exit code, however long that takes. There is **no maximum lifetime for a promoted
   * command**: the runaway guard is the concurrency cap, which the command holds a slot
   * in until it finishes or is stopped.
   */
  fun awaitExit(): Int = process.waitFor()

  /**
   * The kill, in the one order that was measured to work:
   *
   * > Snapshot the descendant tree, then signal **both the snapshotted tree and the process
   * > group**, **TERM**, wait a **5-second grace**, then **SIGKILL**.
   *
   * Both arms are required and neither alone is sufficient: a command that calls `setsid`
   * leaves the group while staying a descendant, and one that traps TERM is never reaped by
   * TERM. The tree is snapshotted **before** the kill, since after reparenting there is nothing
   * left to walk.
   *
   * What comes back is what is still alive, and of each whether it was signalled at all. A
   * snapshot is an observation of ancestry, not containment — it cannot hold a process forked
   * after it was taken — so this reports rather than pretends.
   */
  fun reap(): List<Survivor> {
    val snapshot = term()
    awaitDeath(snapshot, grace)
    return kill(snapshot)
  }

  /**
   * The first half of [reap]: the snapshot, taken and then **TERMed on both arms**, with the
   * grace not yet waited. Split out for Runtime Stop alone, which reaps every running command
   * **in parallel so that Stop costs one grace period in total, not one per command** —
   * the halves are never otherwise apart, which is why [reap] is what every other caller has.
   */
  fun term(): List<ProcessHandle> {
    val snapshot = listOf(process.toHandle()) + process.descendants().use { it.toList() }
    snapshot.forEach { it.destroy() } // TERM, one handle at a time: the tree arm.
    signalGroup("-TERM") // The group arm, for what has left the tree already.
    return snapshot
  }

  /** The second half of [reap], after the grace has been waited: SIGKILL, and what survived it. */
  fun kill(snapshot: List<ProcessHandle>): List<Survivor> {
    val signalled = snapshot.map { it.pid() }.toSet()
    // Walked now rather than at the end, while a parent that trapped TERM is still here to be
    // walked through: once it goes, what it forked is reparented and no longer reachable from
    // here. Anything this finds arrived after the snapshot and is not going to be signalled.
    val late = process.descendants().use { it.toList() }.filterNot { it.pid() in signalled }

    snapshot.filter { it.isAlive }.forEach { it.destroyForcibly() }
    signalGroup("-KILL")
    awaitDeath(snapshot, KILL_SETTLE)

    return snapshot.filter { it.isAlive }.map { Survivor(it.pid(), signalled = true) } +
      late.filter { it.isAlive }.map { Survivor(it.pid(), signalled = false) }
  }

  /** The grace this execution waits between TERM and SIGKILL, which Stop waits once for all. */
  internal val killGrace: Duration get() = grace

  /**
   * What the command has said **so far**, without disturbing the drain. This is the same buffer
   * the frontend's one-line preview and its expanded tail read, which is what keeps the screen
   * and the tool agreeing about what exists.
   */
  fun captured(): BoundedText = captured.text()

  /**
   * What the command said, bounded, with the drain ended. It is given a moment to finish
   * and then has its pipe closed under it: a grandchild that outlived the kill still holds the
   * write end, and waiting on it would be waiting on the very process the kill failed to reap.
   */
  fun output(): BoundedText {
    draining.join(DRAIN_SETTLE.inWholeMilliseconds)
    if (draining.isAlive) {
      try {
        process.inputStream.close()
      } catch (_: IOException) {
        // Already closed; the drain is ending either way.
      }
      draining.join(DRAIN_SETTLE.inWholeMilliseconds)
    }
    return captured()
  }

  private fun awaitDeath(handles: List<ProcessHandle>, within: Duration) {
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    while (System.nanoTime() < deadline && handles.any { it.isAlive }) Thread.sleep(POLL.inWholeMilliseconds)
  }

  /** `kill -TERM -- -PGID`: a negative target selects a group by its absolute value. */
  private fun signalGroup(signal: String) {
    val target = group ?: return
    try {
      ProcessBuilder(signaller, signal, "--", "-$target")
        .redirectErrorStream(true)
        .redirectOutput(DISCARD)
        .start()
        .waitFor(SIGNAL_TIMEOUT_MS, MILLISECONDS)
    } catch (_: IOException) {
      // No usable `kill` on this machine. The tree arm is what is left, and [reap] says what
      // survived it rather than claiming the group was signalled.
    }
  }
}

/**
 * The process group to signal, or null where there is none to signal safely.
 *
 * Read from the child's own `/proc` entry rather than assumed to be its pid: `setsid` execs in
 * place in this launch arrangement, so the two do agree — but the lesson this was built from
 * says to verify how the group is obtained rather than to trust the arrangement, and a guessed
 * group id signals somebody else's processes.
 *
 * It is read **until it has changed**, because the child is started before `setsid` has run and
 * until then still carries the **Runtime's own** group. That is the case worth spelling out:
 * signalling the Runtime's group takes the Runtime down with the command, so it is never the
 * target — and a group read a microsecond too early is exactly how it would become one.
 *
 * The wait is bounded and ends the moment the child exits, so a command that fails to start a
 * session of its own costs a fraction of a second and leaves the tree arm to do the work alone.
 */
private fun observeGroup(process: Process): Long? {
  val own = processGroupOf(ProcessHandle.current().pid())
  val deadline = System.nanoTime() + SESSION_WAIT.inWholeNanoseconds
  while (true) {
    val read = processGroupOf(process.pid()) ?: return null // It has gone; nothing to signal.
    if (read != own) return read
    if (System.nanoTime() >= deadline) return null
    Thread.sleep(1)
  }
}

/**
 * The process group of [pid], from Linux's own account of it. The second field is the command
 * name and may hold both spaces and parentheses, so the fields after it are counted from the
 * **last** `)` rather than by splitting the line.
 */
private fun processGroupOf(pid: Long): Long? = try {
  val stat = Files.readString(Path.of("/proc/$pid/stat"))
  // state, ppid, pgrp.
  stat.substring(stat.lastIndexOf(')') + 1).trim().split(' ')[2].toLongOrNull()
} catch (_: IOException) {
  null
} catch (_: IndexOutOfBoundsException) {
  null
}

/**
 * One command, from the slot it needs to the reply it reaches.
 *
 * [budget] is configuration (ADR 0002): a test sets a short one and watches promotion happen,
 * rather than waiting out the Runtime's real 45 seconds. What happens at the budget is the
 * decision ADR 0003 records: the command is **Promoted**, not killed. Killing it mid-flight is
 * exactly what manufactures **Uncertain**, and letting it finish produces something known.
 *
 * Two paths hand the command over to [commands] and return without it:
 *
 * - the **budget** ran out, and the call is answered at once with a Handle;
 * - the call was **cancelled**, so its answer is discarded — and the work is not. The Operation
 *   runs to completion, its result is kept under its Handle, and its entry reads Undelivered.
 */
internal suspend fun runCommand(
  op: Operation.RunCommand,
  workspace: Workspace,
  directory: Path,
  arrival: Arrival,
  runner: CommandRunner,
  budget: Duration,
  commands: CommandRuntime,
): Outcome<CommandReply> {
  if (op.command.isBlank()) return Outcome.Failed(
    Failure.InvalidArgument("command"),
    "This Operation failed and changed nothing: command is required and cannot be blank.",
  )
  // Before anything is started, so that the refusal below is literally true.
  if (!commands.takeSlot()) return Outcome.Failed(
    Failure.CommandCapReached(commands.cap),
    "This Operation failed and changed nothing: ${commands.cap} commands are already running on " +
      "this machine, which is the limit. Nothing was started, so this call is safe to repeat " +
      "with the same request_id once one of them has finished.",
  )
  var handedOver = false
  try {
    val execution = try {
      runner.start(op.command, directory)
    } catch (failure: IOException) {
      val detail = failure.message ?: failure.toString()
      return Outcome.Failed(
        Failure.IoError(workspace.name, detail),
        "This Operation failed and changed nothing: the command could not be started: $detail",
      )
    }
    val running = commands.begin(arrival.entry, workspace.name, op, execution)
    val exit = try {
      // What is left of the budget, not the whole of it: the budget is measured from frame arrival, and
      // admission has already spent some of it resolving a Root and confining a path.
      runInterruptible { execution.awaitExit(arrival.remaining(budget)) }
    } catch (discarded: CancellationException) {
      // Work is never abandoned. The answer is gone, so the Runtime carries the command
      // to completion, keeps its result under its Handle and marks the entry Undelivered.
      //
      // The cancellation is not swallowed by that: it is rethrown here, and the pipeline above
      // leaves the entry to the coroutine carrying it rather than closing it as Uncertain.
      handedOver = true
      commands.promote(running, Promotion.Discarded)
      throw discarded
    } catch (failed: Throwable) {
      execution.reap()
      execution.output()
      throw failed
    }
    if (exit == null) {
      // At the budget the command keeps running and the call returns at once with a
      // Handle. Promotion is automatic and is never requested.
      handedOver = true
      return Outcome.Ok(commands.promote(running, Promotion.Budget))
    }
    // It exited inside its call — but a Runtime Stop signalling it is also an exit, and that is
    // a reaping rather than a result.
    if (running.beingStopped) return running.stopped().also { running.outcome = it }
    val captured = execution.output()
    val result = CommandResult(op.command, op.label, exit, captured.text, captured.droppedBytes)
    // Its outcome, on the record the Runtime keeps of work in flight: a Stop arriving now has
    // nothing left to end here, and knowing that is what keeps it from reaping a dead process.
    running.outcome = Outcome.Ok(result)
    return Outcome.Ok(CommandReply.Finished(result))
  } finally {
    // The slot goes back the moment this command stops holding one. A promoted command still
    // holds its own, and gives it back in the coroutine carrying it.
    if (!handedOver) commands.releaseSlot()
  }
}

/** What a reaping left behind, as the one sentence a model and a user both read. */
internal fun List<Survivor>.describe(): String = when {
  isEmpty() -> "Nothing was left running."
  else -> "Still running after the stop: " + joinToString("; ") {
    "pid ${it.pid}" + if (it.signalled) " (signalled, did not exit)" else " (never signalled: it " +
      "was started after the tree was snapshotted and had already left the process group)"
  }
}
