package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** How often the one shared grace looks again at what is still alive. */
private val POLL_INTERVAL = 20.milliseconds

/**
 * How long Stop waits for every Operation it ended to have recorded its outcome. What is being
 * waited for is the account catching up with a process that has already been killed, so it is
 * the same wait the kill itself gets rather than a number of its own.
 */
private val SETTLE_TIMEOUT = Operation.KILL_GRACE

/**
 * How many finished promoted commands this Runtime keeps a collectable result for — at
 * [Operation.OUTPUT_CAP_BYTES] apiece, so it is what the Runtime is willing to hold for people
 * who have not come back. Beyond it, the oldest are dropped and a Handle naming one resolves
 * through Activity instead, which is the account it named all along: what is lost is the exit
 * code and the output as *data*, never the record that the command ran.
 *
 * It is not a retention policy for the account: Activity's own 30 days is that, and an
 * Unclaimed entry does not expire until it is settled.
 */
private const val RESULTS_KEPT = 16

/**
 * One Delivery, as the pipeline knows it before any Operation-specific step runs: the Activity
 * entry it opened, and when the frame arrived.
 *
 * The pairing is the point. SPEC §6.2 measures the budget **from frame arrival**, not from the
 * moment a process starts — admission resolves a Root and stats a path, and time spent there is
 * time the transport has already spent waiting. No response deadline is forwarded to this
 * machine, so a mark taken here is the only clock there is.
 */
internal class Arrival(
  val entry: ActivityEntryId,
  private val at: TimeMark = TimeSource.Monotonic.markNow(),
) {
  /** What is left of [budget]. Never negative: a budget already gone is none, not a rollover. */
  fun remaining(budget: Duration): Duration = at.remaining(budget)
}

/** What is left of [budget] since this mark: the one clock a Delivery has (§6.2). */
internal fun TimeMark.remaining(budget: Duration): Duration = (budget - elapsedNow()).coerceAtLeast(Duration.ZERO)

/** Why a command was handed to the Runtime, which is what its entry reads as once it finishes. */
internal enum class Promotion {
  /**
   * The 45-second budget ran out and the call was answered with a Handle (§6.2). Nobody has
   * heard the outcome yet, so the entry reads **Unclaimed** until a `get_result` carries it
   * through or the user acknowledges it.
   */
  Budget,

  /**
   * The answer was discarded — the call that started the work is gone. **Work is never
   * abandoned**: the Operation runs to completion, its result is retained under its Handle, and
   * the entry reads **Undelivered**, because the Runtime knows what happened and only the reply
   * was lost (§6.2).
   */
  Discarded,
}

/**
 * The Runtime-scoped home of every `run_command` that is running (SPEC §6.2, §6.3).
 *
 * Three things live here together because they are one fact about this machine rather than
 * three: the **4-slot cap**, which a promoted command holds a slot in until it finishes or is
 * stopped; the **scope promoted work runs in**, which deliberately outlives the request
 * coroutine that started it; and **Stop**, which is the only thing that needs to reach every
 * running command at once.
 *
 * The scope carries a [SupervisorJob], so one promoted command failing does not cancel another,
 * and nothing that happens to a request coroutine reaches the work it started.
 */
internal class CommandRuntime(
  private val activity: Activity,
  /**
   * SPEC §6.3, §6.6: `run_command` takes no lock — there is nothing meaningful to lock on a
   * command with full account authority — and is capped instead at this many concurrent
   * executions per Runtime, so a model in a retry loop cannot spawn twenty builds and take the
   * machine down. Configuration so a test can ask what the fifth call does without starting four.
   */
  val cap: Int = Operation.COMMAND_CONCURRENCY_CAP,
) {
  private val slots = Semaphore(cap)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Keyed by the Activity entry the Handle names, which is the only identity a command has. */
  private val commands = ConcurrentHashMap<String, InFlightCommand>()
  private val sequence = AtomicLong()

  /** After Stop begins, new calls are refused (§6.3). */
  @Volatile
  var stopping: Boolean = false
    private set

  /** One of the [cap] slots, or nothing — in which case the caller starts no command at all. */
  fun takeSlot(): Boolean = slots.tryAcquire()

  fun releaseSlot() = slots.release()

  /** Registers a command that has just started, so that Stop can reach it. */
  fun begin(
    entry: ActivityEntryId,
    workspace: String,
    op: Operation.RunCommand,
    execution: CommandExecution,
  ): InFlightCommand = InFlightCommand(entry, workspace, op, execution, sequence.incrementAndGet())
    .also { commands[entry.value] = it }

  /**
   * This Operation's outcome is now in the account. For a command that finished inside its own
   * call that is the end of it: no Handle was ever handed out, so there is nothing for anybody
   * to collect and nothing to keep. A promoted command settles itself, in the coroutine
   * carrying it.
   *
   * It is the *pipeline* that calls this rather than the command path, because settled means
   * recorded: Stop waits on it, and a Stop that returned before the account was written would
   * be the Runtime ending work it had not yet said it ended.
   */
  fun recorded(entry: ActivityEntryId) {
    val command = commands[entry.value] ?: return
    if (command.promoted) return
    commands.remove(entry.value)
    command.settled.complete(Unit)
  }

  /**
   * Hands a running command to the Runtime and answers its call with a Handle (§6.2).
   *
   * The launch is on this class's own scope rather than on the caller's, which is the whole
   * mechanism: the request coroutine returns immediately, the work does not, and the Operation
   * still reaches exactly one outcome and still records it.
   */
  fun promote(command: InFlightCommand, why: Promotion): CommandReply.Promoted {
    command.why = why
    command.promoted = true
    // Before the launch, so a frontend hears of the promotion before it can hear of the end.
    activity.promoted(command.entry)
    scope.launch { carryToCompletion(command) }
    return CommandReply.Promoted(Handle(command.entry), command.report())
  }

  /**
   * What the first Delivery of a command whose answer was discarded would have answered: its
   * Handle. Null for anything this Runtime is not carrying past its call.
   */
  fun promotedReply(entry: ActivityEntryId): CommandReply.Promoted? =
    commands[entry.value]?.takeIf { it.promoted }?.let { CommandReply.Promoted(Handle(it.entry), it.report()) }

  /**
   * What a repeat Delivery hands over, given the reply its first Delivery recorded (§6.4).
   *
   * A repeat of a Promoted command gets **the same Handle**, never a second command, and a
   * Handle is not the answer: it carries nothing through. The exception is a command whose
   * first answer was *discarded*: nobody was told anything, so once it has finished the
   * repeat is the second delivery window for its outcome and hands that over instead. While it
   * is still running the repeat hands over its Handle, and that is a change to how it settles:
   * the model then holds a Handle, so the command finished is Unclaimed rather than Undelivered.
   *
   * A discarded command whose result is no longer held hands over the Handle alone, and a
   * `get_result` with it reads the account and settles the entry from there.
   */
  fun handOver(entry: ActivityEntryId, reply: Outcome<*>): Handover {
    val handedHandle = reply is Outcome.Ok && reply.value is CommandReply.Promoted
    if (!handedHandle) return Handover(reply, carried = true)
    val held = commands[entry.value] ?: return Handover(reply, carried = false)
    // Against the coroutine carrying the command, which reads [InFlightCommand.why] when it
    // records the outcome: the two decide together whether the Handle was delivered first.
    synchronized(held) {
      if (held.why != Promotion.Discarded) return Handover(reply, carried = false)
      val outcome = held.outcome ?: run {
        held.why = Promotion.Budget
        return Handover(reply, carried = false)
      }
      return Handover(outcome.asReply(), carried = true)
    }
  }

  /**
   * What a running command has said so far — the buffer `get_result` reads, taken the same way
   * (§6.5). Null once it has an outcome, or for an entry that is no command here.
   */
  fun output(entry: ActivityEntryId): RunningCommand? =
    commands[entry.value]?.takeIf { it.outcome == null }?.report()

  /**
   * `get_result` (§4). The Access Level was re-checked by the pipeline above, which is what
   * closes the collecting route when a Workspace drops below Command.
   */
  fun collect(op: Operation.GetResult, workspace: Workspace): Outcome<Collected> {
    if (op.handle.isBlank()) return Outcome.Failed(
      Failure.InvalidArgument("handle"),
      "This Operation failed and changed nothing: handle is required and cannot be blank.",
    )
    val handle = Handle.of(op.handle)
    val held = commands[op.handle]
    if (held != null) {
      // A command still inside its own call was never promoted, so no Handle for it exists and
      // one presented here names nothing this Runtime handed out.
      if (!held.promoted) return noSuchHandle(op.handle)
      if (held.workspace != workspace.name) return notHere(op.handle, workspace.name)
      val outcome = held.outcome ?: return Outcome.Ok(Collected.StillRunning(handle, held.report()))
      // Collecting is what settles the Unclaimed entry: a later arrival carries the outcome
      // through, and nobody has to have seen it. Once, though — `get_result` is idempotent, and
      // an entry that read "4 deliveries" for one command nobody delivered twice would be the
      // account inventing the transport's behaviour rather than revealing it (§10.1).
      if (held.claimCarried()) activity.delivery(held.entry, carried = true)
      return Outcome.Ok(Collected.Reached(handle, outcome))
    }
    // Not held: the Handle names an entry in the account, which is what it named all along.
    val entry = activity.entry(handle.entry) ?: return noSuchHandle(op.handle)
    if (entry.workspace != workspace.name) return notHere(op.handle, workspace.name)
    // Lost carries nothing through — there is no result to carry — so it stays unresolved until
    // the user acknowledges it, which is the one route left once the Runtime that ran it is gone.
    if (entry.outcome is ActivityOutcome.Unclaimed || entry.outcome is ActivityOutcome.Undelivered) {
      activity.delivery(entry.id, carried = true)
    }
    return Outcome.Ok(Collected.Recorded(handle, entry))
  }

  /**
   * Runtime Stop (§6.3): refuse new calls, then apply the one kill to every running Operation —
   * **in parallel, so Stop costs one grace period in total, not one per command**.
   *
   * Work in flight is left **Uncertain**, not **Lost**: the Runtime chose to end it and knows
   * that it did. Lost is reserved for a Runtime taken from the machine.
   */
  suspend fun stop() {
    stopping = true
    val running = commands.values.filter { it.outcome == null }
    running.forEach { it.beingStopped = true }
    // TERM on both arms of every command first, and only then the wait: the other order is one
    // grace per command, which is what this split exists to avoid.
    val termed = running.map { it to it.execution.term() }
    running.forEach { activity.stopping(it.entry, StopPhase.Terminating) }
    val grace = running.maxOfOrNull { it.execution.killGrace } ?: Duration.ZERO
    awaitDeath(termed.map { (_, snapshot) -> snapshot }, grace)
    termed.forEach { (command, snapshot) ->
      activity.stopping(command.entry, StopPhase.Killing)
      command.reapedWith(command.execution.kill(snapshot))
    }
    // Work is never abandoned, and neither is the account of it: every Operation this ended has
    // its outcome written before Stop is over, whether the request coroutine or the one
    // carrying a promoted command is what writes it.
    withTimeoutOrNull(SETTLE_TIMEOUT) { running.forEach { it.settled.await() } }
    // Whatever has still not reached an outcome is one the kill could not end — a process that
    // outlived SIGKILL. The Runtime says so itself rather than leaving the entry open, because
    // an open entry reads back **Lost**, and Lost is reserved for a Runtime taken from the
    // machine. This one chose to end the work and knows that it did.
    running.filter { !it.settled.isCompleted }.forEach { command ->
      if (command.claimRecord()) {
        activity.complete(command.entry, OperationCatalog.recordOf(command.op, command.unreapable().asReply()))
      }
    }
    scope.cancel()
  }

  /**
   * StopOperation (SPEC §9): the same kill as [stop], applied to one command, with new calls
   * still admitted. False when [entry] names no command still running here — one that already
   * finished has nothing left to end, and only a command runs long enough to be worth stopping.
   */
  suspend fun stopOne(entry: ActivityEntryId): Boolean {
    val command = commands[entry.value]?.takeIf { it.outcome == null } ?: return false
    // There is no second, harder stop: one already under way is the only one there is.
    synchronized(command) {
      if (command.beingStopped) return true
      command.stoppedByUser = true
      command.beingStopped = true
    }
    val snapshot = command.execution.term()
    activity.stopping(entry, StopPhase.Terminating)
    awaitDeath(listOf(snapshot), command.execution.killGrace)
    activity.stopping(entry, StopPhase.Killing)
    command.reapedWith(command.execution.kill(snapshot))
    withTimeoutOrNull(SETTLE_TIMEOUT) { command.settled.await() }
    // What [stop] does with a process that outlived SIGKILL, for the same reason: an entry left
    // open would read back Lost, and this Runtime chose to end the work and knows that it did.
    if (!command.settled.isCompleted && command.claimRecord()) {
      activity.complete(command.entry, OperationCatalog.recordOf(command.op, command.unreapable().asReply()))
    }
    return true
  }

  private suspend fun carryToCompletion(command: InFlightCommand) {
    try {
      val exit = runInterruptible { command.execution.awaitExit() }
      val outcome = if (command.beingStopped) command.stopped() else {
        val captured = command.execution.output()
        Outcome.Ok(
          CommandResult(command.op.command, command.op.label, exit, captured.text, captured.droppedBytes),
        )
      }
      if (command.claimRecord()) record(command, outcome)
    } finally {
      // The slot goes back here and nowhere else: a promoted command holds one of the four
      // until it finishes or is stopped (§6.3).
      slots.release()
      command.settled.complete(Unit)
    }
  }

  private fun record(command: InFlightCommand, outcome: Outcome<CommandResult>) = synchronized(command) {
    activity.complete(command.entry, OperationCatalog.recordOf(command.op, outcome.asReply()))
    // Only an answer worth hearing goes astray. An Uncertain is already unresolved and already
    // says the truer thing about this machine, and wrapping it would hide it behind a word
    // about who heard it.
    if (outcome is Outcome.Ok) when (command.why) {
      Promotion.Budget -> activity.unclaimed(command.entry)
      Promotion.Discarded -> activity.undelivered(command.entry)
    }
    // **Last**, and the order is the decision. Activity settles an entry by reading the records
    // in the order they were appended, and a carry appended *before* the Unclaimed it settles
    // is superseded by it — leaving an entry nobody can ever settle and a result already
    // collected. Publishing the outcome only once its records are down is what keeps a
    // `get_result` landing in this window answering "still running", which it truthfully is
    // as far as the account goes.
    command.outcome = outcome
    evictOldestResults()
  }

  /** The oldest finished results go first; the entry they belong to stays in the account. */
  private fun evictOldestResults() {
    val finished = commands.values.filter { it.promoted && it.outcome != null }
    if (finished.size <= RESULTS_KEPT) return
    finished.sortedBy { it.order }.take(finished.size - RESULTS_KEPT)
      .forEach { commands.remove(it.entry.value) }
  }

  /** One wait for every snapshot at once, which is what makes Stop cost one grace period. */
  private suspend fun awaitDeath(snapshots: List<List<ProcessHandle>>, within: Duration) {
    if (snapshots.isEmpty()) return
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    while (System.nanoTime() < deadline && snapshots.any { snapshot -> snapshot.any { it.isAlive } }) {
      delay(POLL_INTERVAL)
    }
  }

  private fun noSuchHandle(handle: String): Outcome.Failed = Outcome.Failed(
    Failure.NoSuchHandle(handle),
    "This Operation failed and changed nothing: no command on this machine answers to handle " +
      "'$handle'. A handle is returned by run_command when a command outruns the call that " +
      "started it, and it is the only thing get_result takes.",
  )

  private fun notHere(handle: String, workspace: String): Outcome.Failed = Outcome.Failed(
    Failure.HandleNotInWorkspace(handle, workspace),
    "This Operation failed and changed nothing: handle '$handle' was not returned by a command " +
      "in Workspace '$workspace'. Collect it by naming the Workspace the command ran in.",
  )
}

/**
 * One command between starting and reaching an outcome. It is the Runtime's own record of work
 * in flight, not a second account of it: what happened is written to Activity, and the entry
 * this names is the one a Handle points at.
 */
internal class InFlightCommand(
  val entry: ActivityEntryId,
  /** The Workspace the call named, which is the one a Handle may be collected through (§4). */
  val workspace: String,
  val op: Operation.RunCommand,
  val execution: CommandExecution,
  /** Arrival order, which is what "oldest result" means when results are evicted. */
  val order: Long,
) {
  private val since = TimeSource.Monotonic.markNow()
  val startedAt: Instant = Instant.now()

  /** It outran its call, or its call was discarded, and the Runtime is carrying it now. */
  @Volatile
  var promoted: Boolean = false

  /**
   * Why it was handed to the Runtime, which is only read once it has been. It can move from
   * [Promotion.Discarded] to [Promotion.Budget] once, when a repeat Delivery hands its Handle
   * over after all; both that and the read happen holding this command's monitor.
   */
  var why: Promotion = Promotion.Budget

  /** A Runtime Stop has signalled it, so what it exits with is a reaping rather than a result. */
  @Volatile
  var beingStopped: Boolean = false

  /** The user stopped this one command, rather than the Runtime stopping everything. */
  @Volatile
  var stoppedByUser: Boolean = false

  /** What the reaping left behind, completed by the Stop that did the reaping. */
  private val reaped: CompletableDeferred<List<Survivor>> = CompletableDeferred()

  @Volatile
  private var reaping: List<Survivor> = emptyList()

  /** Null while it is still running. Set once, by whichever coroutine sees it end. */
  @Volatile
  var outcome: Outcome<CommandResult>? = null

  /** Completed once this Operation's outcome is in the account. Stop waits on it. */
  val settled: CompletableDeferred<Unit> = CompletableDeferred()

  private val written = AtomicBoolean(false)
  private val carried = AtomicBoolean(false)

  /**
   * True for whichever of the coroutine carrying this command and the Stop ending it arrives
   * first.
   * One Operation is recorded once, and the two can race at the moment a Stop times out waiting.
   */
  fun claimRecord(): Boolean = written.compareAndSet(false, true)

  /** True the first time an outcome is collected, which is the arrival that settles the entry. */
  fun claimCarried(): Boolean = carried.compareAndSet(false, true)

  /** What it has said so far, taken without disturbing the drain (§6.5). */
  fun report(): RunningCommand {
    val captured = execution.captured()
    return RunningCommand(op.command, op.label, since.elapsedNow(), captured.text, captured.droppedBytes)
  }

  /**
   * A command the Runtime ended: **Uncertain**, naming the pids the reaping could not reach.
   * Not Lost — the Runtime chose to end it and knows that it did.
   */
  suspend fun stopped(): Outcome.Uncertain {
    val survivors = reaped.await()
    // The drain is ended rather than left behind: a grandchild that outlived the kill still
    // holds the write end of the pipe, and the thread reading it would outlive the Operation.
    execution.output()
    return endedByStop(survivors)
  }

  /** The survivors the kill named, for a Stop that had to write this Operation's record itself. */
  fun reapedWith(survivors: List<Survivor>) {
    reaping = survivors
    reaped.complete(survivors)
  }

  /**
   * What the kill could not end, said by the Stop itself because the coroutine carrying this
   * command is still waiting on a process that outlived SIGKILL. It names the survivors the
   * kill already found rather than reaping again, which would cost a second grace inside Stop.
   */
  fun unreapable(): Outcome.Uncertain = endedByStop(reaping)

  /**
   * The one sentence a Runtime Stop leaves behind, in the one shape §6.3 gives it: **Uncertain**
   * rather than `failed`, since the command had already begun and `failed`'s "nothing changed"
   * cannot be promised of it — and naming the pids the reaping could not reach, because
   * survivors are never rounded off to "stopped".
   */
  private fun endedByStop(survivors: List<Survivor>): Outcome.Uncertain = Outcome.Uncertain(
    Uncertainty.Stopped(survivors),
    "This command was stopped because ${if (stoppedByUser) "the user stopped it" else "the Runtime was stopped"}. " +
      "It had already begun, so what " +
      "it did to this machine is unknown — ${Outcome.Uncertain.EFFECTS_UNCERTAIN}. " +
      survivors.describe(),
  )
}

/** What a repeat Delivery answers, and whether that carries the Operation's answer through. */
internal data class Handover(val answer: Outcome<*>, val carried: Boolean)

/** What Activity records of a command: the reply shape, so one renderer covers both halves. */
internal fun Outcome<CommandResult>.asReply(): Outcome<CommandReply> = when (this) {
  is Outcome.Ok -> Outcome.Ok(CommandReply.Finished(value))
  is Outcome.Failed -> this
  is Outcome.Uncertain -> this
}
