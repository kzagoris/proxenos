package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one home every Operation passes through:
 *
 * > resolve Workspace -> check Access Level -> confine path -> check the Delivery key ->
 * > take the real-path lock -> write Activity -> shape the Outcome
 *
 * Request-shaped rather than method-shaped precisely so that eleven call sites cannot each
 * forget a step. Adding an Operation adds a branch to [dispatch] and a catalog entry beside it;
 * the steps around them are not reachable to opt out of.
 *
 * It is not itself a [WorkspaceOperations]: [operationsFor] hands one view per surface, with
 * that surface's Origin stamped into it at wiring time.
 */
class WorkspaceOperationsPipeline internal constructor(
  private val registry: WorkspaceRegistry,
  private val activity: Activity,
  /**
   * How long a `search` is given before it answers with what it has. Configuration rather
   * than a literal for the same reason the tunnel budget is: a test sets a short one and
   * exercises the marker, instead of building a project big enough to run the real one out.
   */
  private val searchBudget: Duration,
  /**
   * The mutation lock. Handed in for the same reason the budget is: holding it is the
   * only way to ask, in a test and without a race, what a call does while a mutation on that
   * path is in flight — which is how "reads take no lock" is a fact rather than a claim.
   */
  private val locks: PathLocks,
  /**
   * How `git` is started. Handed in like the budget above, so a test can ask what the
   * three Git tools do with no usable `git` on the machine without uninstalling it.
   */
  private val git: GitTools = GitTools(),
  /**
   * How long a `run_command` is given before it is **Promoted**. Configuration for the
   * same reason as the two above: a test sets a short one and watches promotion happen, instead
   * of waiting out the Runtime's real 45 seconds.
   */
  private val commandBudget: Duration = Operation.COMMAND_BUDGET,
  /**
   * How a command is started and stopped. Handed in so a test can shorten the grace
   * between TERM and SIGKILL, which is otherwise five seconds of every reaping test.
   */
  private val runner: CommandRunner = CommandRunner(),
  /**
   * The Delivery records. Handed in so a test can shorten the 10 minutes a record is
   * kept, and ask what a key reused after expiry is answered without waiting them out.
   */
  private val deliveries: Deliveries = Deliveries(),
  /** Concurrent commands per Runtime. Configuration, like the budget above it. */
  commandConcurrency: Int = Operation.COMMAND_CONCURRENCY_CAP,
) {
  /**
   * Where a command that outran its call goes, the cap on concurrent commands, and the
   * Stop that reaches both. Held by the pipeline because all three are per **Runtime** and not
   * per Workspace: one model in a retry loop reaching four Workspaces is the case the cap
   * exists for, and Stop ends every Operation there is.
   */
  private val commands = CommandRuntime(activity, commandConcurrency)

  constructor(
    registry: WorkspaceRegistry,
    activity: Activity,
    searchBudget: Duration = Operation.SEARCH_BUDGET,
  ) : this(registry, activity, searchBudget, PathLocks())

  /** What the composition root builds: every tunable from the one [RuntimeConfig]. */
  constructor(registry: WorkspaceRegistry, activity: Activity, config: RuntimeConfig) : this(
    registry,
    activity,
    Operation.SEARCH_BUDGET,
    PathLocks(),
    commandBudget = config.commandBudget,
    runner = CommandRunner(grace = config.killGrace),
    deliveries = Deliveries(config.deliveryRetention, config.deliveryRecordQuota, config.deliveryKeyQuota),
    commandConcurrency = config.commandConcurrency,
  )

  val catalog: List<OperationSpec> = OperationCatalog.ENTRIES

  /**
   * The view one surface calls through — one for the `mcp` adapter, one for the control
   * socket — with its [Origin] bound here and nowhere else. `perform` takes no origin
   * argument, so a caller cannot claim an Origin that is not its own: a bug in the MCP adapter
   * cannot write "Frontend" against a ChatGPT command, which is the one record anybody relies
   * on after an unattended `run_command`.
   */
  fun operationsFor(origin: Origin): WorkspaceOperations = Surface(origin)

  private inner class Surface(private val origin: Origin) : WorkspaceOperations {
    override val catalog: List<OperationSpec> get() = this@WorkspaceOperationsPipeline.catalog
    override suspend fun <R> perform(op: Operation<R>): Outcome<R> =
      this@WorkspaceOperationsPipeline.perform(origin, op)
  }

  /**
   * Safe to call concurrently from any dispatcher. The blocking `java.nio` work below runs on
   * an I/O dispatcher chosen here rather than by the caller: an adapter that forgot to switch
   * would starve the SDK's CPU-sized pool, and that failure looks like an unrelated hang.
   */
  private suspend fun <R> perform(origin: Origin, op: Operation<R>): Outcome<R> = withContext(Dispatchers.IO) {
    // Marked first, because the budget runs from the frame's arrival and not from the first
    // step this Runtime chooses to take with it.
    val arrivedAt = TimeSource.Monotonic.markNow()
    val spec = OperationCatalog.specFor(op)
    val scoped = op as? Operation.Scoped<*>

    // A repeat Delivery is answered before anything else, and above admission on purpose: an
    // Access Level lowered between Deliveries does not change the answer. A repeat is
    // not a new decision by anybody, the change is already on disk, and refusing would withhold
    // from the model that the mutation happened. It opens no entry of its own either — an
    // Operation is recorded once, and each later Delivery appends a fact against it.
    val key = scoped?.deliveryKey?.takeIf { it.isNotBlank() }
    var claim = key?.let { deliveries.claim(it, op) }
    while (claim is Deliveries.Claim.Repeat) {
      repeat<R>(claim.record, arrivedAt)?.let { return@withContext it }
      // The first Delivery failed and released the key: nothing changed, so this arrival is a
      // first Delivery after all, rather than a `failed` passed off as a recorded result.
      claim = deliveries.claim(key!!, op)
    }
    val first = (claim as? Deliveries.Claim.First)?.record

    // Open the entry first, above admission rather than beside the work: a refused call is
    // recorded as an ordinary `failed` entry, because a model repeatedly naming a Workspace you
    // withheld is exactly what the unattended account exists to show. The Workspace recorded is
    // the name the call gave, whether or not it resolves.
    val arrival = try {
      Arrival(activity.open(origin, scoped?.workspace, spec.name, OperationCatalog.argumentsOf(op)), arrivedAt)
    } catch (unrecorded: Throwable) {
      // Nothing was started, so a repeat waiting on this key is owed exactly that.
      first?.let { deliveries.replied(it, notRecorded(unrecorded)) }
      throw unrecorded
    }
    val entry = arrival.entry
    first?.entry?.complete(entry)
    val outcome = try {
      admitAndRun(op, spec, scoped, arrival, claim)
    } catch (interrupted: Throwable) {
      // Cancellation, or a defect. Either way this Runtime is still here to say what happened,
      // and an entry left open would read back as Lost — which is reserved for an Operation the
      // Runtime was taken from. What it did to disk is genuinely unknown, so it says so.
      //
      // Unless the work outlived the call: a `run_command` whose answer was discarded is still
      // running, and closing its entry here would be this Runtime saying an Operation ended
      // that it is at this moment carrying to completion. What its first Delivery would
      // have answered is then the Handle, and a repeat is owed that.
      val carried = commands.promotedReply(entry)
      val reply: Outcome<*> = if (carried != null) Outcome.Ok(carried) else {
        val uncertain = Outcome.Uncertain(
          Uncertainty.Interrupted,
          "This Operation did not complete: $interrupted. Its effects on disk are unknown — " +
            Outcome.Uncertain.EFFECTS_UNCERTAIN + ".",
        )
        activity.complete(entry, ActivityOutcome.Uncertain(uncertain.message))
        uncertain
      }
      commands.recorded(entry)
      first?.let { deliveries.replied(it, reply) }
      throw interrupted
    }
    // A Promoted reply precedes its outcome, so there is nothing to complete yet: the
    // entry stays open, reads InFlight, and is closed by the Runtime-scoped coroutine carrying
    // the command. Every other Operation is completed here.
    if (!outcome.isPromotion()) {
      // Nothing written above is touched; this is a second record, and its absence after a
      // restart is what reads back as Lost.
      activity.complete(entry, OperationCatalog.recordOf(op, outcome))
    }
    // Settled means recorded: a Stop waits on this rather than on the work alone, so it never
    // returns having ended an Operation it had not yet said it ended.
    commands.recorded(entry)
    // The reply is kept only once it is in the account, so a repeat never answers ahead of it.
    first?.let { deliveries.replied(it, outcome) }
    outcome
  }

  /** A reply that arrived before its Operation's outcome exists, and so closes no entry. */
  private fun Outcome<*>.isPromotion(): Boolean = this is Outcome.Ok && value is CommandReply.Promoted

  /** Everything between the two Activity records: the admission steps, then the work itself. */
  private suspend fun <R> admitAndRun(
    op: Operation<R>,
    spec: OperationSpec,
    scoped: Operation.Scoped<*>?,
    arrival: Arrival,
    claim: Deliveries.Claim?,
  ): Outcome<R> {
    // Stop refuses new calls before it reaps anything, so nothing starts that the kill
    // below it would then have to end. A `failed`, because nothing ran.
    if (commands.stopping) return Outcome.Failed(
      Failure.RuntimeStopping,
      "This Operation failed and changed nothing: this Runtime is stopping and is not taking " +
        "new calls. Nothing was started.",
    )

    // Resolve the Workspace, then check the Access Level. Both live in the registry, which
    // answers a withheld Workspace exactly as it answers an unregistered one.
    val workspace = scoped?.let {
      // The reason stays NoSuchWorkspace: an absent argument resolves to no Workspace, and
      // the exposed names a frontend would show are the same either way.
      val named = it.workspace ?: return missingWorkspaceArgument()
      val required = checkNotNull(spec.requiredLevel) { "Scoped '${spec.name}' states no required level" }
      registry.admit(named, required).valueOr { problem -> return problem }
    }

    // Confine every path argument to the Root. `run_command`'s command is bounded by none of
    // this — the Root is routing context for it, not confinement — but its optional `cwd` is a
    // path argument like any other and is confined here.
    val confined = scoped?.confinedPaths.orEmpty().map { argument ->
      confine(workspace!!, argument).valueOr { problem -> return problem }
    }

    // Check the Delivery key, before the lock so that nothing is done twice. Which Operations
    // carry one is the catalog's to say, and the two saying different things would be a key
    // nobody checks.
    val keyed = spec.arguments.any { it.name == Operation.KEY_ARGUMENT }
    require(keyed == (scoped?.deliveryKey != null)) {
      "'${spec.name}' ${if (keyed) "takes" else "takes no"} ${Operation.KEY_ARGUMENT}, so its Operation must " +
        "${if (keyed) "carry" else "carry no"} one"
    }
    // Required means required: a blank key is the argument not arriving, and an Operation that
    // mutates without one cannot be told from a repeat of itself.
    if (scoped?.deliveryKey?.isBlank() == true) return Outcome.Failed(
      Failure.MissingKey,
      "This Operation failed and changed nothing: ${Operation.KEY_ARGUMENT} is required. Supply a fresh " +
        "unique one for each operation you intend to perform.",
    )
    // The key was looked up on arrival, above admission, because a repeat is answered whatever
    // the Access Level now is. What was neither a repeat nor a first Delivery is refused
    // here, in the pipeline's order — and a first Delivery refused anywhere above released its
    // key, since `failed` left nothing a second execution could do twice.
    when (claim) {
      is Deliveries.Claim.Conflict -> return Outcome.Failed(
        Failure.KeyConflict(claim.key),
        "This Operation failed and changed nothing: ${Operation.KEY_ARGUMENT} '${claim.key}' " +
          "already names a different operation, so nothing was performed for this call. A " +
          "${Operation.KEY_ARGUMENT} names one operation; supply a fresh unique one for each " +
          "operation you intend to perform.",
      )
      is Deliveries.Claim.Expired -> return Outcome.Failed(
        Failure.KeyExpired(claim.key),
        "This Operation failed and changed nothing: ${Operation.KEY_ARGUMENT} expired; use a " +
          "new id only for an intentional new execution. The operation '${claim.key}' named was " +
          "performed earlier, and its recorded result is no longer held.",
      )
      else -> Unit
    }

    // Take the real-path lock — for a mutation only; reads take none. The key is the
    // target's resolved real path, so overlapping Roots reaching one file contend on it. It is
    // the path already resolved above, never a second resolution: between two of them a symlink
    // can move, and a mutation holding the lock for a path it is not writing to locks nothing.
    val target = scoped?.mutatedPath?.let { mutated ->
      val index = scoped.confinedPaths.indexOf(mutated)
      require(index >= 0) { "'$mutated' must be one of ${spec.name}'s confined paths" }
      confined[index]
    }
    return if (target == null) shape(op, workspace, mutating = false, paths = confined, arrival = arrival)
    else locks.withPathLock(target.toString()) {
      shape(op, workspace, mutating = true, paths = confined, arrival = arrival)
    }
  }

  /** The last step: one of ok / failed / uncertain, and never an exception escaping the core. */
  private suspend fun <R> shape(
    op: Operation<R>,
    workspace: Workspace?,
    mutating: Boolean,
    paths: List<Path>,
    arrival: Arrival,
  ): Outcome<R> = try {
    dispatch(op, workspace, paths, arrival)
  } catch (failure: IOException) {
    val detail = failure.message ?: failure.toString()
    // A Root that breaks mid-operation is not policed mid-operation; it surfaces as whatever
    // the I/O did. A read guarantees nothing changed, a mutation cannot.
    if (mutating) Outcome.Uncertain(
      // Mutating means Scoped, and a Scoped Operation reaches here only through admission.
      Uncertainty.RootBrokeMidOperation(requireNotNull(workspace).name),
      "This Operation did not complete: $detail. Its effects on disk are unknown — " +
        Outcome.Uncertain.EFFECTS_UNCERTAIN + ".",
    ) else Outcome.Failed(
      Failure.IoError(workspace?.name, detail),
      "This Operation failed and changed nothing: $detail",
    )
  }

  @Suppress("UNCHECKED_CAST") // Each sealed request fixes its result type.
  private suspend fun <R> dispatch(
    op: Operation<R>,
    workspace: Workspace?,
    paths: List<Path>,
    arrival: Arrival,
  ): Outcome<R> = when (op) {
    Operation.ListWorkspaces -> Outcome.Ok(registry.listings())
    // A Scoped Operation reaches here only through admission, which cannot yield a null.
    is Operation.ReadFile -> readFile(op, workspace!!, paths.single())
    is Operation.ListDirectory -> listDirectory(op, workspace!!, paths.single())
    is Operation.Search -> search(op, workspace!!, paths.single(), searchBudget)
    is Operation.GitStatus -> gitStatus(workspace!!, paths.single(), git)
    is Operation.GitDiff -> gitDiff(op, workspace!!, paths.single(), git)
    is Operation.GitLog -> gitLog(op, workspace!!, paths.single(), git)
    is Operation.WriteFile -> writeFile(op, workspace!!, paths.single())
    is Operation.EditFile -> editFile(op, workspace!!, paths.single())
    // No lock above: a command with full account authority has nothing meaningful to lock on,
    // and it is the concurrency cap inside that guards the machine instead.
    is Operation.RunCommand -> runCommand(op, workspace!!, paths.single(), arrival, runner, commandBudget, commands)
    // It names no path, so there is none in [paths]: what it collects already ran, wherever it
    // was confined to then. The Access Level was re-checked above like any other call's, which
    // is the whole of what closes the route when a Workspace drops below Command.
    is Operation.GetResult -> commands.collect(op, workspace!!)
  } as Outcome<R>

  /**
   * A repeat Delivery: the first reply **verbatim**, marked as the recorded result of an
   * Operation already performed, or a wait on the first while it is still in flight.
   *
   * The wait is the repeat's own, measured from its own arrival: a second delivery window
   * rather than a second execution. If the first is still running when that budget runs out
   * the answer is `uncertain`, never `failed` — the first is changing the disk at that moment,
   * so "nothing changed" would be a lie.
   */
  @Suppress("UNCHECKED_CAST") // The record was claimed by an equal Operation, so of this R.
  private suspend fun <R> repeat(record: Deliveries.Record, arrivedAt: TimeMark): Outcome<R>? {
    val reply = if (record.reply.isCompleted) record.reply.getCompleted()
    else withTimeoutOrNull(arrivedAt.remaining(commandBudget)) { record.reply.await() }
    // Released, so there is no recorded result to hand over: the caller claims the key afresh.
    if (reply is Outcome.Failed) return null
    // The entry is opened microseconds after the claim, and before any reply other than a
    // `failed` one exists — so it is there whenever there is a reply, and nearly always when
    // there is none. The rare repeat that outwaits a first Delivery before its entry exists
    // has nothing to append against, and says so by appending nothing.
    val entry = if (record.entry.isCompleted) record.entry.getCompleted() else null
    if (reply == null) {
      entry?.let { activity.delivery(it, carried = false) }
      return Outcome.Uncertain(
        Uncertainty.FirstDeliveryInFlight(record.key),
        "This call's ${Operation.KEY_ARGUMENT} '${record.key}' arrived before, and the operation " +
          "it started is still running, so its result does not exist yet and it was not " +
          "started a second time. Its effects on disk are not known yet — " +
          Outcome.Uncertain.EFFECTS_UNCERTAIN + " with a new ${Operation.KEY_ARGUMENT}; repeat " +
          "this call with the same ${Operation.KEY_ARGUMENT} to receive the original result.",
      )
    }
    // What is handed over is the first reply, or — for a command whose first answer was
    // discarded — what that answer has become since.
    val handover = commands.handOver(checkNotNull(entry), reply)
    activity.delivery(entry, handover.carried)
    return (handover.answer as Outcome<R>).asRecorded()
  }

  /** The account could not be opened, so nothing was started — which is exactly `failed`. */
  private fun notRecorded(failure: Throwable): Outcome.Failed {
    val detail = failure.message ?: failure.toString()
    return Outcome.Failed(
      Failure.IoError(null, detail),
      "This Operation failed and changed nothing: this machine's account could not be written, " +
        "so nothing was started: $detail",
    )
  }

  /**
   * Runtime **Stop**. A frontend reaches it as `ManagementAct.Stop`, and the
   * shutdown hook reaches it directly: the act is the same either way.
   *
   * New calls are refused first, then the one kill is applied to every running Operation **in
   * parallel, so Stop costs one grace period in total, not one per command**. What was running
   * is left **Uncertain**, not **Lost** — the Runtime chose to end it and knows that it did.
   */
  suspend fun stop() = commands.stop()

  /** `ManagementAct.StopOperation`: one running command ended, left Uncertain. False if [entry] names none. */
  suspend fun stopOperation(entry: ActivityEntryId): Boolean = commands.stopOne(entry)

  /** `ManagementAct.ReadOutput`: the live buffer `get_result` would read, or null. */
  fun outputOf(entry: ActivityEntryId): RunningCommand? = commands.output(entry)

  private fun missingWorkspaceArgument(): Outcome.Failed {
    val exposed = registry.exposedNames()
    return Outcome.Failed(
      Failure.NoSuchWorkspace(exposed),
      "This call must name a workspace. There is no default, not even when exactly one " +
        "Workspace is exposed. Exposed Workspaces: ${exposed.joinToString()}",
    )
  }

}

/** Carries a Failed or Uncertain straight out, keeping each step of a pipeline to one line. */
internal inline fun <T> Outcome<T>.valueOr(onProblem: (Outcome<Nothing>) -> Nothing): T = when (this) {
  is Outcome.Ok -> value
  is Outcome.Failed -> onProblem(this)
  is Outcome.Uncertain -> onProblem(this)
}
