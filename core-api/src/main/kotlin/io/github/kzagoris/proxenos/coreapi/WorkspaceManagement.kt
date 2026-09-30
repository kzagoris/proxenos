@file:UseSerializers(InstantSerializer::class)

package io.github.kzagoris.proxenos.coreapi

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * What a frontend does. Implemented twice, and only here in the design: by the core
 * in-process, and by the management client over the control socket. A frontend is handed this
 * type and never learns which one it holds.
 */
interface WorkspaceManagement {
  suspend fun <R> perform(act: ManagementAct<R>): R

  /**
   * A complete [RuntimeEvent.Snapshot] first, then [RuntimeEvent.Change]s, on one ordered
   * stream. One stream rather than fetch-then-subscribe, because a change landing between the
   * two calls would be lost and the screen silently wrong until the next unrelated one.
   * Any number of collectors may be attached at once; none of them excludes another.
   */
  fun observe(): Flow<RuntimeEvent>
}

@Serializable
sealed interface ManagementAct<R> {
  /**
   * The acts that are a change to a registration, which is the registry's to make. Grouped so
   * that the acts which are not — [Acknowledge], and the Runtime's own lifetime — do not
   * arrive at the registry as a branch it has to refuse.
   */
  @Serializable
  sealed interface OnRegistry<R> : ManagementAct<R>

  @Serializable
  data class Register(val root: String, val name: String? = null) : OnRegistry<Workspace>
  @Serializable
  data class Rename(val id: WorkspaceId, val name: String) : OnRegistry<Workspace>
  /** At [AccessLevel.None] this is **Revoke**: per-Workspace, and it stops nothing already running. */
  @Serializable
  data class SetLevel(val id: WorkspaceId, val level: AccessLevel) : OnRegistry<Workspace>
  /** Omitting root re-confirms the current Root; supplying it rebinds the same Workspace id. */
  @Serializable
  data class Reconfirm(val id: WorkspaceId, val root: String? = null) : OnRegistry<Workspace>
  /** The registration is gone. Its Activity entries stay: the account is not the registry's to edit. */
  @Serializable
  data class Forget(val id: WorkspaceId) : OnRegistry<Unit>

  /**
   * The user has seen an entry's unresolved outcome. It **appends** a fact and
   * settles the entry by folding; it never edits the entry, which is the one thing Activity
   * forbids. So *when* an unattended command was noticed is itself part of the account.
   */
  @Serializable
  data class Acknowledge(val entry: ActivityEntryId) : ManagementAct<Unit>

  /**
   * The user says their connector in ChatGPT was built against the catalog this Runtime serves
   * (ADR 0007), which stores the current catalog fingerprint. It records the user's
   * word and **not a measurement**: nothing on this machine can see the connector, so nothing
   * here checks that the steps were done.
   */
  @Serializable
  data object AcknowledgeConnector : ManagementAct<Unit>

  /**
   * Takes the transport down. The Runtime stays up, registrations are untouched, and
   * it changes no Access Level and stops no running work. The intent survives a restart.
   */
  @Serializable
  data object Disconnect : ManagementAct<Unit>

  /** Undoes [Disconnect]. The link is [RuntimeState.Connecting] again until a poll succeeds. */
  @Serializable
  data object Connect : ManagementAct<Unit>

  /**
   * Ends the Runtime itself: new calls are refused, every running Operation
   * is ended and left Uncertain, and the process exits. Not [Disconnect], and not a Revoke.
   */
  @Serializable
  data object Stop : ManagementAct<Unit>

  /**
   * Ends one running Operation, which is left Uncertain like one a [Stop] ended. Only a
   * `run_command` runs long enough to be worth stopping; naming anything else is refused.
   */
  @Serializable
  data class StopOperation(val entry: ActivityEntryId) : ManagementAct<Unit>

  /**
   * What a running command has said so far: the **same buffer** `get_result` reads,
   * taken the same way, so the screen and the tool cannot disagree about what exists. Null once
   * [entry] names no command running here — it finished, or it was never a command.
   */
  @Serializable
  data class ReadOutput(val entry: ActivityEntryId) : ManagementAct<RunningCommand?>

  /**
   * An Operation invoked from a frontend, through the **same pipeline** ChatGPT's calls take,
   * with the Access Level enforced identically: the point of checking a function is to see
   * what ChatGPT gets, and a bypass would show a green tick against a Workspace at None. It is
   * recorded with [Origin.Frontend], and a mutating one carries its own `request_id` in [op].
   */
  @Serializable
  data class TryOperation<R>(val op: Operation<R>) : ManagementAct<Outcome<R>>
}

/**
 * What [WorkspaceManagement.observe] emits. Every [Change] names the whole of what it changes
 * rather than a delta, so [Snapshot.after] applying one twice is harmless — which is what lets
 * a change that lands while a snapshot is being taken arrive twice rather than not at all.
 */
@Serializable
sealed interface RuntimeEvent {
  @Serializable
  data class Snapshot(
    /** Every registration, withheld and Broken ones included: a frontend shows what exists. */
    val workspaces: List<WorkspaceState>,
    val runtime: RuntimeStatus,
    /** Opened by this Runtime and not yet completed, oldest first. */
    val running: List<RunningOperation>,
    /**
     * The account as this Runtime reads it, oldest first, earlier starts' entries included: an
     * entry a previous Runtime left Lost is exactly what a frontend attaching now has to see.
     */
    val activity: List<ActivityEntry> = emptyList(),
    /**
     * This Runtime's start — the one boundary the feed draws. Null only before
     * Activity has been read, which no frontend attaching to a running Runtime can observe.
     */
    val start: RuntimeStart? = null,
    /**
     * No catalog fingerprint the user acknowledged, or one that differs from what this Runtime
     * serves. Derived at every Start, never stored, and says nothing about the link
     * or about whether a connector exists. True until the Runtime has read the acknowledgement,
     * which no frontend attaching to a running Runtime can observe: an unread one is not one.
     */
    val connectorUnconfirmed: Boolean = true,
    /** Words beside a Connecting that has lasted one long-poll wait; never a state. */
    val connectingWords: ConnectingWords? = null,
    /**
     * The catalog ChatGPT is served, as data, so a frontend shows exactly what ChatGPT
     * sees from the one source of truth. Static for the Runtime's life, so no change carries it.
     */
    val catalog: List<OperationSpec> = emptyList(),
  ) : RuntimeEvent {
    /** This state with [change] applied: the one fold both the core and a frontend use. */
    fun after(change: Change): Snapshot = when (change) {
      is Change.WorkspaceChanged -> copy(
        workspaces = if (workspaces.any { it.workspace.id == change.state.workspace.id }) {
          workspaces.map { if (it.workspace.id == change.state.workspace.id) change.state else it }
        } else workspaces + change.state,
      )
      is Change.WorkspaceForgotten -> copy(workspaces = workspaces.filter { it.workspace.id != change.id })
      is Change.RuntimeChanged -> copy(runtime = change.status)
      is Change.OperationStarted -> if (running.any { it.entry == change.operation.entry }) this
      else copy(running = running + change.operation)
      is Change.OperationEnded -> copy(running = running.filter { it.entry != change.entry })
      // Only onto one still running: an Operation that ended is not brought back by news about it.
      is Change.OperationPromoted -> copy(running = running.map { if (it.entry == change.entry) it.copy(promoted = true) else it })
      is Change.OperationStopping -> copy(running = running.map { if (it.entry == change.entry) it.copy(stopping = change.phase) else it })
      is Change.ActivityRead -> copy(activity = change.entries, start = change.start)
      is Change.ConnectorChanged -> copy(connectorUnconfirmed = change.unconfirmed)
      is Change.ConnectingWordsChanged -> copy(connectingWords = change.words)
      is Change.EntryRecorded -> copy(
        activity = if (activity.any { it.id == change.entry.id }) {
          activity.map { if (it.id == change.entry.id) change.entry else it }
        } else activity + change.entry,
      )
    }
  }

  @Serializable
  sealed interface Change : RuntimeEvent {
    @Serializable
    data class WorkspaceChanged(val state: WorkspaceState) : Change
    @Serializable
    data class WorkspaceForgotten(val id: WorkspaceId) : Change
    @Serializable
    data class RuntimeChanged(val status: RuntimeStatus) : Change
    @Serializable
    data class OperationStarted(val operation: RunningOperation) : Change
    @Serializable
    data class OperationEnded(val entry: ActivityEntryId) : Change

    /** A command outran its call, or its call was discarded, and the Runtime is carrying it. */
    @Serializable
    data class OperationPromoted(val entry: ActivityEntryId) : Change

    /** The one kill has reached [phase] on a running command. It is still running until it ends. */
    @Serializable
    data class OperationStopping(val entry: ActivityEntryId, val phase: StopPhase) : Change

    /**
     * The whole account, read afresh: once when the Runtime starts, and again whenever retention
     * drops what it no longer keeps. Whole rather than a list of what expired, so it names all of
     * what it changes like every other [Change].
     */
    @Serializable
    data class ActivityRead(val start: RuntimeStart, val entries: List<ActivityEntry>) : Change

    /** The connector read as Unconfirmed, or not: once at Start, and again when acknowledged. */
    @Serializable
    data class ConnectorChanged(val unconfirmed: Boolean) : Change

    /** The words beside Connecting appeared, changed, or went: whole, like every other change. */
    @Serializable
    data class ConnectingWordsChanged(val words: ConnectingWords?) : Change

    /**
     * One entry as it now folds, after a record was appended against it: opened, completed, a
     * further Delivery, gone astray, or Acknowledged. The entry is re-read, never edited — this
     * is what the account now *says* about it.
     */
    @Serializable
    data class EntryRecorded(val entry: ActivityEntry) : Change
  }
}

/** A registration as a frontend shows it: Broken is not a level, so it sits beside one. */
@Serializable
data class WorkspaceState(val workspace: Workspace, val broken: Boolean)

/** One Operation in flight, named by the Activity entry it opened — which is what [ManagementAct.StopOperation] takes. */
@Serializable
data class RunningOperation(
  val entry: ActivityEntryId,
  val origin: Origin,
  val workspace: String?,
  val tool: String,
  val arguments: String,
  val startedAt: Instant,
  /** Handed to the Runtime past its call: a Handle names it, and only `get_result` collects it. */
  val promoted: Boolean = false,
  /**
   * Where the one kill has got to, or null when nothing is stopping it. A stopping Operation is
   * still running — that is the point of TERM, then grace, then KILL — and it is listed here
   * until it is reaped.
   */
  val stopping: StopPhase? = null,
)

/** The one kill on the machine, as far as it has got. There is no second, harder stop. */
@Serializable
enum class StopPhase {
  /** TERM sent to the snapshotted tree and the process group; the grace is running. */
  Terminating,
  /** The grace is over and SIGKILL was sent; what is left is waiting to be reaped. */
  Killing,
}
