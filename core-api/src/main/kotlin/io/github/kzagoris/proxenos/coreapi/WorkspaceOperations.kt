package io.github.kzagoris.proxenos.coreapi

import kotlinx.serialization.Serializable

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface WorkspaceOperations {
  /** The catalog as data (SPEC §3): the core owns it, an adapter only renders it. */
  val catalog: List<OperationSpec>

  suspend fun <R> perform(op: Operation<R>): Outcome<R>
}

/**
 * One catalog entry. Flat, static, and the same set whatever any Access Level is: a dynamic
 * catalog would ride on `tools/list_changed` reaching ChatGPT, which is not established.
 */
@Serializable
data class OperationSpec(
  val name: String,
  val description: String,
  /**
   * What the named Workspace must allow; null where the entry names no Workspace. Not
   * [AccessLevel.None], which has one meaning already — the dial at which a Workspace is
   * withheld — and would read here as a level an unscoped call somehow requires.
   */
  val requiredLevel: AccessLevel?,
  val arguments: List<ArgumentSpec>,
)

@Serializable
data class ArgumentSpec(
  val name: String,
  val type: ArgumentType,
  val required: Boolean,
  val description: String,
)

@Serializable
enum class ArgumentType { Text, Integer, Flag }

@Serializable
sealed interface Operation<R> {
  @Serializable
  data object ListWorkspaces : Operation<List<WorkspaceListing>>

  /**
   * An Operation against one named Workspace. Every member here is a step of the admission
   * pipeline the Operation cannot opt out of, which is why none of them has a default: adding
   * an Operation is refused by the compiler until its author has said what it confines and
   * what it mutates.
   */
  @Serializable
  sealed interface Scoped<R> : Operation<R> {
    /**
     * Null is the argument not arriving. It is never defaulted — not even when exactly one
     * Workspace is exposed (SPEC §3), because a stale selection edits the right file in the
     * wrong project and nothing in the transcript looks wrong.
     */
    val workspace: String?

    /** Path arguments resolved against the Root and confined to it before the work runs (§2.4). */
    val confinedPaths: List<String>

    /**
     * The path this Operation writes to, whose resolved real path keys the mutation lock
     * (§6.6); null for a read. It must be one of [confinedPaths], so that the path locked and
     * the path written are one resolution: between two of them a symlink can move, and a
     * mutation holding the lock for a path it is not writing to locks nothing.
     */
    val mutatedPath: String?

    /**
     * The Delivery key this Operation carries (§6.4); null for one that carries none, which
     * is every read. A step of the pipeline like the others here, so that a mutating
     * Operation whose author forgot the key does not compile — the key is what tells one
     * arrival of an Operation from a repeat of it, and a mutation without one is the case
     * ADR 0004 exists to prevent.
     *
     * Named for what it is rather than for the wire, where §4 fixes it as `request_id`:
     * "request" is a word the glossary rules out, since one Delivery is not one Operation.
     */
    val deliveryKey: String?
  }

  @Serializable
  data class ReadFile(
    override val workspace: String?,
    val path: String,
    /** 1-based line to start at; null is the first line. */
    val offset: Int? = null,
    /** Lines to return; null is [DEFAULT_LINE_CEILING]. The byte cap may bind first. */
    val limit: Int? = null,
  ) : Scoped<FileContent> {
    override val confinedPaths: List<String> get() = listOf(path)
    override val mutatedPath: String? get() = null
    override val deliveryKey: String? get() = null
  }

  /**
   * The Root's own entries when [path] is absent. The path is confined like any other (§2.4),
   * so a directory reached through a symlink that leaves the Root is refused rather than listed.
   */
  @Serializable
  data class ListDirectory(
    override val workspace: String?,
    /** Directory relative to the Root; null is the Root itself. */
    val path: String? = null,
  ) : Scoped<DirectoryListing> {
    /** The path as it is spoken of: the argument, or the Root itself when it was left out. */
    val label: String get() = path ?: THIS_DIRECTORY
    override val confinedPaths: List<String> get() = listOf(label)
    override val mutatedPath: String? get() = null
    override val deliveryKey: String? get() = null
  }

  /**
   * Filename and content in one call (§4). How it enumerates is a decision rather than a
   * detail: inside a repository it asks Git, which is how the project's own ignore rules apply
   * without anybody reimplementing `.gitignore`.
   */
  @Serializable
  data class Search(
    override val workspace: String?,
    /** Literal substring unless [regex]; never empty, which would match every line there is. */
    val query: String,
    /** Subtree relative to the Root; null is the whole Root. */
    val path: String? = null,
    /** Read [query] as a regular expression rather than as the literal text it is by default. */
    val regex: Boolean = false,
    /** Match case. Off by default, which is what a model half-remembering a name needs. */
    val caseSensitive: Boolean = false,
  ) : Scoped<SearchResults> {
    /** The path as it is spoken of: the argument, or the Root itself when it was left out. */
    val label: String get() = path ?: THIS_DIRECTORY
    override val confinedPaths: List<String> get() = listOf(label)
    override val mutatedPath: String? get() = null
    override val deliveryKey: String? get() = null
  }

  /**
   * The three read-only Git tools (§4). They sit at [AccessLevel.Read], so a Workspace can show
   * what changed without granting command execution; Git *mutation* is not in the first release,
   * and `run_command` reaches it once commands are enabled.
   *
   * None of them takes a path argument, so the path they confine is the Root itself — which is
   * what makes a Root that has moved report as Broken here exactly as it does everywhere else.
   *
   * There is **no snapshot guarantee**: each is one invocation giving a point-in-time view, and
   * a concurrent `run_command` rewriting the repository can make two of them disagree. That is
   * acceptable for read-only diagnostics and is not worth a repository-wide lock.
   */
  @Serializable
  sealed interface Git<R> : Scoped<R> {
    override val confinedPaths: List<String> get() = listOf(THIS_DIRECTORY)
    override val mutatedPath: String? get() = null
    override val deliveryKey: String? get() = null
  }

  /** Parsed from porcelain output rather than from the human-readable form (§4). */
  @Serializable
  data class GitStatus(override val workspace: String?) : Git<GitStatusReport>

  /**
   * The working tree against `HEAD`, staged and unstaged together, unless [staged] asks for the
   * staged changes alone. A flag rather than a revision argument: a tool that takes revisions is
   * a tool that takes `HEAD~500..`, which is no longer a read of what changed here.
   */
  @Serializable
  data class GitDiff(
    override val workspace: String?,
    val staged: Boolean = false,
  ) : Git<GitDiffReport>

  /** The most recent commits, [GIT_LOG_COMMITS] of them when [limit] is absent. */
  @Serializable
  data class GitLog(
    override val workspace: String?,
    val limit: Int? = null,
  ) : Git<GitLogReport>

  /**
   * A file written whole (§4). Missing parent directories are **not** created: a hallucinated
   * path fails loudly rather than growing a tree.
   *
   * It lands by temp file and atomic rename, which replaces the inode — so hardlinks to the
   * file break. That is a consequence on the record rather than one to be discovered later.
   */
  @Serializable
  data class WriteFile(
    override val workspace: String?,
    val path: String,
    val content: String,
    /** Required, and `request_id` on the wire (§6.4): a repeat Delivery of it is answered with
     * the first's reply rather than doing the work again. */
    override val deliveryKey: String,
  ) : Scoped<FileWritten> {
    override val confinedPaths: List<String> get() = listOf(path)
    override val mutatedPath: String get() = path
  }

  /**
   * One byte-exact replacement that must match **exactly once** (§4). Zero matches and several
   * are both refused, and several names the count rather than taking the first: the uniqueness
   * requirement *is* the concurrency check — if another process changed that region the match
   * no longer hits — which is why there is no mtime or hash precondition argument here, and
   * why there is one edit per call rather than a batch whose half could land.
   */
  @Serializable
  data class EditFile(
    override val workspace: String?,
    val path: String,
    /** Byte-exact text that must occur exactly once; never empty, which occurs everywhere. */
    val oldText: String,
    val newText: String,
    /** Required, and `request_id` on the wire (§6.4): a repeat Delivery of it is answered with
     * the first's reply rather than doing the work again. */
    override val deliveryKey: String,
  ) : Scoped<FileWritten> {
    override val confinedPaths: List<String> get() = listOf(path)
    override val mutatedPath: String get() = path
  }

  /**
   * A command run through `/bin/sh -c` (§4) — the shell semantics a model actually writes, and
   * a fixed interpreter rather than whatever `$SHELL` happens to be.
   *
   * **The Root is routing context here, not confinement.** A command at [AccessLevel.Command]
   * runs with the full authority of the user's Linux account, which is wider than the Root: it
   * can read `~/.ssh` whatever §2.4 says about a path argument. [cwd] is a path argument like
   * any other and *is* confined, so the Root stops confining what a command does, not where the
   * Runtime starts it. Mandatory sandboxing was considered and rejected by the user; this is the
   * one place the workspace metaphor stops being true, and it must not be quietly narrowed.
   *
   * It takes no mutation lock (§6.6) — there is nothing meaningful to lock on a command with
   * full account authority — and is bounded instead by [COMMAND_CONCURRENCY_CAP].
   */
  @Serializable
  data class RunCommand(
    override val workspace: String?,
    /** A single command line; never empty, which would start a shell that reads nothing. */
    val command: String,
    /** Working directory relative to the Root; null is the Root itself. */
    val cwd: String? = null,
    /** Required, and `request_id` on the wire (§6.4). */
    override val deliveryKey: String,
  ) : Scoped<CommandReply> {
    /** The working directory as it is spoken of: the argument, or the Root it defaults to. */
    val label: String get() = cwd ?: THIS_DIRECTORY
    override val confinedPaths: List<String> get() = listOf(label)

    /** Null: a command takes no lock, and naming a path here would claim one it does not hold. */
    override val mutatedPath: String? get() = null
  }

  /**
   * Collects a Promoted Operation's output once the call that started it has ended (§4, §6.2).
   *
   * **The Access Level is re-checked here on every call**, and that is the point of the entry
   * rather than a side effect of the pipeline: a Workspace lowered to Read stops the model
   * collecting output from a command already in flight, which is what a user lowering the dial
   * means by it. Note the deliberate asymmetry with a repeat Delivery (§6.4), which serves the
   * stored reply whatever the level now is — that is not a fresh decision by anybody, and this
   * is a call the model chose to make.
   *
   * It carries no Delivery key: collecting twice collects the same thing, so there is no repeat
   * to tell from an intention.
   */
  @Serializable
  data class GetResult(
    override val workspace: String?,
    /**
     * The Handle a Promoted reply carried, as text rather than as a [Handle] for the same
     * reason [workspace] is a `String`: it is what arrived, and whether it names anything is
     * the core's to answer, not something the type can promise on the way in.
     */
    val handle: String,
  ) : Scoped<Collected> {
    /** It names no path: what it collects already ran, wherever it was confined to then. */
    override val confinedPaths: List<String> get() = emptyList()
    override val mutatedPath: String? get() = null
    override val deliveryKey: String? get() = null
  }

  companion object {
    /** SPEC §4: the default ceiling, above which a model pages deliberately. */
    const val DEFAULT_LINE_CEILING: Int = 2000

    /** SPEC §6.5: 64 KiB per Operation. Truncation is never silent. */
    const val OUTPUT_CAP_BYTES: Int = 64 * 1024

    /**
     * SPEC §6.5: a cut output keeps both ends — where it began and how it ended. The two are
     * derived from the one cap rather than written down again, so they cannot drift past it.
     */
    const val OUTPUT_HEAD_BYTES: Int = OUTPUT_CAP_BYTES / 2
    const val OUTPUT_TAIL_BYTES: Int = OUTPUT_CAP_BYTES - OUTPUT_HEAD_BYTES

    /**
     * What a search returns at most (§4). A search is a locator — the model reads what it
     * found with `read_file` — so the cap is on hits rather than on bytes, and it is low
     * enough that a one-letter query over a large project is answered rather than endured.
     */
    const val SEARCH_RESULT_CAP: Int = 200

    /**
     * How much of a matching line is quoted. A minified bundle's one line is a locator like
     * any other, and quoting all of it would spend the whole reply on a single hit.
     */
    const val SEARCH_LINE_CAP_BYTES: Int = 1024

    /**
     * §4, §6.2: search is time-bounded rather than Promoted, and the bound sits well inside
     * the Runtime's 45s budget so that what it found is still serialized and answered.
     */
    val SEARCH_BUDGET: Duration = 30.seconds

    /** SPEC §4: what a `git_log` with no `limit` returns. */
    const val GIT_LOG_COMMITS: Int = 20

    /**
     * §4, §6.2: how long one Git invocation is given. There is no Promotion for a Git tool and
     * none of them can plausibly take this long, so the budget is not a bound anybody is meant
     * to meet — it is what keeps a `git` that has parked on a slow filesystem from holding a
     * Runtime thread for the rest of the process's life. Well inside the 45s call budget, so
     * the refusal is still answered.
     */
    val GIT_BUDGET: Duration = 30.seconds

    /**
     * §6.2: the Runtime-wide budget, measured from frame arrival, with no per-call override —
     * the model cannot buy time the transport will not give it, and asking for more produces
     * *more executions* rather than a longer one. A configured constant the Runtime owns and
     * re-cuts when the transport is re-measured, never a value derived from anything.
     */
    val COMMAND_BUDGET: Duration = 45.seconds

    /**
     * §6.3, §6.6: the runaway guard, since a command takes no lock and has no maximum lifetime.
     * It counts Promoted commands too — a promoted command holds its slot until it finishes or
     * is stopped — and sits comfortably inside the transport's 10 concurrent requests.
     */
    const val COMMAND_CONCURRENCY_CAP: Int = 4

    /**
     * §6.3: how long the one kill on this machine waits between TERM and SIGKILL. Long enough
     * for a build to put its own children down, short enough that a Runtime Stop is one grace
     * period and not a hang.
     */
    val KILL_GRACE: Duration = 5.seconds

    /** What a path argument means when it is left out: the Root itself. */
    const val THIS_DIRECTORY: String = "."

    /**
     * SPEC §3: the mandatory routing argument's name on the wire. Here rather than in the
     * catalog alone because an adapter has to read it out of a call by that name, and a
     * catalog that said `workspace` while an adapter read `workspace_name` would route
     * every call to no Workspace at all.
     */
    const val WORKSPACE_ARGUMENT: String = "workspace"

    /**
     * SPEC §6.4: the Delivery key's name on the wire. Named once for the same reason
     * [WORKSPACE_ARGUMENT] is — the catalog declares it, an adapter reads it and the
     * pipeline checks it, and three spellings of one argument is a key nobody checks.
     */
    const val KEY_ARGUMENT: String = "request_id"
  }
}

/**
 * What a read returned, and enough about what it did not: [totalLines] against [firstLine] and
 * [lineCount] is how the model knows what it has not seen.
 */
@Serializable
data class FileContent(
  val path: String,
  val text: String,
  val firstLine: Int,
  val lineCount: Int,
  val totalLines: Int,
  /**
   * The byte cap, not the line ceiling, ended this read. Truncation is never silent (§6.5);
   * a read is cut at the head rather than head-and-tail because it is the offset a model
   * pages with, and a hole in the middle of a file is not something to page past.
   */
  val cappedByBytes: Boolean,
)

/**
 * What a mutation left on disk. [bytes] is what was written, which is not [WriteFile.content]'s
 * length: the file's existing line-ending and trailing-newline convention is preserved, so what
 * lands can differ from what was handed over by exactly those bytes.
 */
@Serializable
data class FileWritten(
  val path: String,
  val bytes: Int,
  /** The file did not exist before this write. Always false for an edit, which needs one that does. */
  val created: Boolean,
)

/**
 * What a command did. A non-zero [exitCode] is an [Outcome.Ok] and not an [Outcome.Failed]: the
 * Operation ran the command it was asked to, and `failed` carries a guarantee — nothing on disk
 * changed — that a command which ran and exited 1 cannot make. What the command thought of
 * itself is [exitCode]'s to say, and the model reads it.
 *
 * Standard output and standard error arrive **interleaved in one stream**, in the order the
 * command wrote them, because that is the account a command gives of itself and because §6.5
 * bounds one buffer per Operation rather than two that would each need half a cap.
 */
@Serializable
data class CommandResult(
  val command: String,
  /** Where it ran, as it was spoken of: the `cwd` argument, or `.` for the Root it defaults to. */
  val cwd: String,
  val exitCode: Int,
  val output: String,
  /**
   * Bytes the 64 KiB bound dropped from the **middle** (§6.5); 0 when the output is whole. The
   * middle is what a long build repeats; the two ends are where the command said what it was
   * doing and how it ended.
   */
  val droppedBytes: Int,
) {
  val cappedByBytes: Boolean get() = droppedBytes > 0
}

/**
 * What a `run_command` call is answered with (SPEC §6.2). Two shapes, because a command that
 * outran the 45-second budget is **Promoted** rather than killed, and its reply then precedes
 * its outcome.
 *
 * It is not a fourth [Outcome]: the set stays `ok` / `failed` / `uncertain` and every Operation
 * still reaches exactly one of them. The distinction lives in this type so that nothing can
 * quietly render a command still running as a command that succeeded — a renderer that forgot
 * [Promoted] does not compile.
 */
@Serializable
sealed interface CommandReply {
  /** It finished inside the call that started it, which is what almost every command does. */
  @Serializable
  data class Finished(val result: CommandResult) : CommandReply

  /**
   * It outran the call and is still running (§6.2). **Never worded as success**: no outcome
   * exists yet, and the only thing this says about the command is that it has not ended.
   *
   * Promotion is automatic and is never asked for — there is no `background` argument, because
   * that would make the model predict how long its own command takes, and it is wrong about
   * that.
   */
  @Serializable
  data class Promoted(val handle: Handle, val running: RunningCommand) : CommandReply
}

/** A command that has not finished, and what it has said so far. */
@Serializable
data class RunningCommand(
  val command: String,
  /** Where it runs, as it was spoken of: the `cwd` argument, or `.` for the Root it defaults to. */
  val cwd: String,
  /** How long it has been running when this was taken. */
  val elapsed: Duration,
  /**
   * The live buffer, bounded by the same 64 KiB that bounds everything else (§6.5). It is the
   * buffer the frontend's preview reads too, which is what keeps the screen and the tool
   * agreeing about what exists.
   */
  val outputSoFar: String,
  /** Bytes the bound has dropped from the middle so far; 0 while the output is whole. */
  val droppedBytes: Int,
)

/**
 * What a Handle resolved to (SPEC §4, §6.2). `get_result` returns either Promoted again — still
 * running, with the output captured so far — or the finished Operation with its outcome.
 */
@Serializable
sealed interface Collected {
  val handle: Handle

  /** Promoted again: it is still running, and this is what it has said so far. */
  @Serializable
  data class StillRunning(override val handle: Handle, val running: RunningCommand) : Collected

  /**
   * The Operation reached its outcome, and [outcome] is the one it reached — collecting it is
   * what settles the **Unclaimed** entry the Handle names.
   *
   * The `get_result` Operation's *own* Outcome is an `ok` whatever this holds: collecting
   * succeeded, and it changed nothing on disk either way. An Uncertain collected here belongs
   * to the entry the Handle names, which already carries it, and duplicating it onto this call
   * would put a second unresolved entry in the account for one command.
   */
  @Serializable
  data class Reached(override val handle: Handle, val outcome: Outcome<CommandResult>) : Collected

  /**
   * The Handle names an entry this Runtime is not holding a result for: one a previous Runtime
   * promoted — which reads back [ActivityOutcome.Lost], the Handle resolving to the Lost
   * Operation it points at — or one whose result this Runtime no longer keeps.
   *
   * What Activity recorded, and nothing invented beside it.
   */
  @Serializable
  data class Recorded(override val handle: Handle, val entry: ActivityEntry) : Collected
}

/**
 * A process the one kill (§6.3) left running. **Survivors are never rounded off to "stopped"**:
 * a reaping that did not finish names the pids still alive, and says of each whether it was
 * signalled at all — one that forked after the snapshot and had already left the group was not,
 * and saying it was stopped would be a plain untruth about this machine.
 */
@Serializable
data class Survivor(val pid: Long, val signalled: Boolean)

/**
 * One directory's entries, sorted by name. [totalEntries] against `entries.size` is how the
 * model knows what it has not seen — there is no paging here, so the count is the whole of the
 * promise the marker makes.
 */
@Serializable
data class DirectoryListing(
  val path: String,
  val entries: List<DirectoryEntry>,
  val totalEntries: Int,
  /**
   * Bytes of names the cap left out (§6.5); 0 when the listing is whole. Truncation is never
   * silent, and the marker names a byte count like every other.
   *
   * Cut 32 KiB head and 32 KiB tail, like a command's output: a listing has no offset to page
   * by, so what is dropped is dropped for good, and both ends of a sorted listing say more
   * about what a directory holds than one end and a count would.
   */
  val droppedBytes: Int,
) {
  val cappedByBytes: Boolean get() = droppedBytes > 0
}

/** A symlink is reported as one rather than as what it points at, which is not followed here. */
@Serializable
data class DirectoryEntry(val name: String, val kind: EntryKind, val sizeBytes: Long?)

/** What an entry is, taken without following it. Other is a socket, a device, a fifo. */
@Serializable
enum class EntryKind { File, Directory, Symlink, Other }

/**
 * What a search found, and what stopped it. [enumeration] is part of the answer rather than a
 * detail: a plain walk saw files the project ignores, and a model reading `build/` output as
 * though it were source is a different kind of wrong from finding nothing.
 */
@Serializable
data class SearchResults(
  val path: String,
  val enumeration: Enumeration,
  val hits: List<SearchHit>,
  val filesSearched: Int,
  val bounds: SearchBounds,
)

/** How the files to search were enumerated (§4). */
@Serializable
enum class Enumeration {
  /** `git ls-files --cached --others --exclude-standard`: the project's own ignore rules. */
  GitIgnoreRules,

  /** No repository above the subtree, or no usable `git`: a plain walk skipping `.git/`. */
  PlainWalk,
}

/** One filename or one matching line. Both are Root-relative paths a `read_file` can take. */
@Serializable
sealed interface SearchHit {
  val path: String

  /** The path itself matched the query. */
  @Serializable
  data class Name(override val path: String) : SearchHit

  @Serializable
  data class Content(
    override val path: String,
    /** 1-based, so it is the `offset` the next `read_file` is made with. */
    val line: Int,
    val text: String,
    /** The line was longer than [Operation.SEARCH_LINE_CAP_BYTES] and is quoted cut. */
    val lineCut: Boolean,
  ) : SearchHit
}

/**
 * Every way this search returned less than it might have. All four are `ok` (§4): a search is
 * read-only and capped by design, so having found less is a result, never an [Outcome.Uncertain].
 */
@Serializable
data class SearchBounds(
  /** [Operation.SEARCH_RESULT_CAP] was reached, so enumeration stopped early. */
  val cappedByResults: Boolean,
  /** [Operation.SEARCH_BUDGET] ran out: truncated by time, with what it found so far. */
  val cappedByTime: Boolean,
  /** Bytes dropped from the middle by the 64 KiB head-and-tail bound (§6.5); 0 when none were. */
  val droppedBytes: Int,
  /** How many hits those bytes were. */
  val droppedHits: Int,
  /**
   * Lines whose tail went unmatched because holding one whole would have cost more memory than
   * a line is worth. A minified bundle's single line is the case: the query may sit past the
   * point this search read to, so a miss in such a file is not proof the query is absent.
   */
  val linesNotFullySearched: Int,
) {
  val complete: Boolean
    get() = !cappedByResults && !cappedByTime && droppedHits == 0 && linesNotFullySearched == 0
}

@Serializable
sealed interface Outcome<out R> {
  /**
   * This is the first Delivery's reply, answered again to a repeat Delivery of the same key
   * (§6.4): the recorded result of an Operation already performed, which was not performed a
   * second time. The reply beside it is the first one **verbatim**; this is the sentence that
   * says so, and every surface is obliged to carry it — see [RECORDED_RESULT].
   */
  val recorded: Boolean

  @Serializable
  data class Ok<R>(val value: R, override val recorded: Boolean = false) : Outcome<R>

  /** Carries a hard guarantee: nothing on disk changed, so a retry is safe. */
  @Serializable
  data class Failed(
    val reason: Failure,
    val message: String,
    override val recorded: Boolean = false,
  ) : Outcome<Nothing>

  /**
   * Effects unknown. The message is obliged to carry [EFFECTS_UNCERTAIN] verbatim, because
   * `failed` and `uncertain` are the two a model will otherwise conflate (ADR 0001) — and
   * checking it here is what stops the eleventh call site being the one that paraphrases it.
   */
  @Serializable
  data class Uncertain(
    val reason: Uncertainty,
    val message: String,
    override val recorded: Boolean = false,
  ) : Outcome<Nothing> {
    init {
      require(EFFECTS_UNCERTAIN in message) {
        "An Uncertain message must say '$EFFECTS_UNCERTAIN' in words a model acts on: $message"
      }
    }

    companion object {
      const val EFFECTS_UNCERTAIN: String = "effects uncertain, do not retry"
    }
  }

  companion object {
    /**
     * What a [recorded] reply says beside the first reply (§6.4). Written once, in the core's
     * words, so that no surface paraphrases a repeat into something that reads like a second
     * execution — or like a fresh one the model should act on as new.
     */
    const val RECORDED_RESULT: String =
      "This is the recorded result of this request_id's first delivery: it arrived before, and " +
        "the operation was not performed again."
  }
}

/** The same reply, marked as the recorded result of an Operation already performed (§6.4). */
@Suppress("UNCHECKED_CAST") // Each variant keeps its own type; only the marker changes.
fun <R> Outcome<R>.asRecorded(): Outcome<R> = when (this) {
  is Outcome.Ok -> copy(recorded = true)
  is Outcome.Failed -> copy(recorded = true)
  is Outcome.Uncertain -> copy(recorded = true)
} as Outcome<R>

/** For the frontends, which have to act; the message beside it is for the model. */
@Serializable
sealed interface Failure {
  @Serializable
  data class NoSuchWorkspace(val exposedWorkspaces: List<String>) : Failure
  @Serializable
  data class WorkspaceBroken(val workspace: String) : Failure
  @Serializable
  data class LevelTooLow(val workspace: String, val current: AccessLevel, val required: AccessLevel) : Failure

  /** [symlink] is the Root-relative link that led out, null when `..` or an absolute path did. */
  @Serializable
  data class OutsideRoot(val workspace: String, val path: String, val symlink: String? = null) : Failure
  @Serializable
  data class NotAFile(val workspace: String, val path: String) : Failure
  @Serializable
  data class NotADirectory(val workspace: String, val path: String) : Failure

  /**
   * `edit_file` found no occurrence of its `old_text`. Nothing changed, so this is a `failed`
   * like any other — and it is also what a region another process has already changed looks
   * like, which is the whole of the tool's concurrency check.
   */
  @Serializable
  data class NoMatch(val workspace: String, val path: String) : Failure

  /** `edit_file` found [count] occurrences where it needs one. Never first-one-wins (§4). */
  @Serializable
  data class SeveralMatches(val workspace: String, val path: String, val count: Int) : Failure

  /**
   * Nothing above the Root is a Git repository, so there is nothing for a Git tool to read. The
   * tool is in the catalog all the same — the catalog is flat and static (§3) — and this is the
   * plain error it answers with.
   */
  @Serializable
  data class NoRepository(val workspace: String, val root: String) : Failure

  /**
   * There is a repository and it could not be asked: no usable `git`, one too broken to answer,
   * or one that did not answer inside [Operation.GIT_BUDGET].
   *
   * For `search` that means its ignore rules could not be read, and it is refused rather than
   * walked — a plain walk would hand back the very files the project ignores, and doing that
   * quietly is the one outcome a user who wrote a `.gitignore` would not forgive. For the Git
   * tools it is the plain error §4 gives them when `git` is absent. Distinct from
   * [NoRepository], because the two name different things to fix: install `git`, against a Root
   * that is not in a repository at all.
   */
  @Serializable
  data class GitUnavailable(val workspace: String, val repository: String) : Failure
  @Serializable
  data class Binary(val workspace: String, val path: String) : Failure
  @Serializable
  data class InvalidArgument(val argument: String) : Failure

  /**
   * The Handle names nothing this Runtime can resolve — not a promoted command it holds, and
   * not an entry still in the account. Its own reason rather than an [InvalidArgument]: a
   * frontend has nothing to offer for a Handle that has expired out of a 30-day account, and
   * something quite different to offer for one that was mistyped.
   */
  @Serializable
  data class NoSuchHandle(val handle: String) : Failure

  /**
   * The Handle resolves, and to an Operation against another Workspace (§4). Refused rather
   * than served: a Workspace is the unit the Access Level dial governs, and a Handle that
   * crossed between them would collect output from a Workspace this call never named.
   */
  @Serializable
  data class HandleNotInWorkspace(val handle: String, val workspace: String) : Failure

  /**
   * The Runtime is Stopping, so this call was refused before anything was started (§6.3).
   * Deliberately a `failed` like [CommandCapReached]: **nothing ran**, so the "nothing changed"
   * guarantee is literally true, and nothing about it is unresolved.
   */
  @Serializable
  data object RuntimeStopping : Failure

  /**
   * [Operation.COMMAND_CONCURRENCY_CAP] commands are already running, so this one did not start
   * (§6.3). Deliberately a `failed` rather than an [Uncertainty]: **nothing ran**, so a retry is
   * safe, which is the guarantee doing its job rather than being worked around.
   */
  @Serializable
  data class CommandCapReached(val cap: Int) : Failure

  /**
   * A mutation arrived without its Delivery key (§6.4). Its own reason rather than an
   * [InvalidArgument]: it is the one missing argument that says the caller cannot tell its
   * own repeats apart, which is a different thing for a frontend to act on.
   */
  @Serializable
  data object MissingKey : Failure

  /**
   * The key arrived before with different arguments (§6.4). Literally a `failed`: nothing
   * changed on *this* Delivery. Keys are unique Runtime-wide, so the same key naming another
   * Workspace lands here too, which is the safe direction.
   */
  @Serializable
  data class KeyConflict(val key: String) : Failure

  /**
   * The key's record has expired and the bare key is still remembered (§6.4), so this is
   * refused rather than silently run a second time.
   */
  @Serializable
  data class KeyExpired(val key: String) : Failure
  @Serializable
  data class IoError(val workspace: String?, val detail: String) : Failure
}

@Serializable
sealed interface Uncertainty {
  /**
   * Every Uncertainty a reaping can produce names what it left behind. Empty for an Operation
   * with no child process, which is every one but `run_command`.
   */
  @Serializable
  sealed interface Reaped : Uncertainty {
    /** Never defaulted: a reaping that says nothing about what it left is one nobody can act on. */
    val survivors: List<Survivor>
  }

  /**
   * The Operation outran the budget it had and was reaped by it.
   *
   * **Nothing produces this any more, and that is the change ADR 0003 made.** A `run_command`
   * past the budget is Promoted rather than killed; a Git tool past its own is `failed`, since
   * nothing ran that could have changed anything; and a `search` past its own is an `ok` with
   * less in it. Killing at a budget was the one thing manufacturing this outcome, and it is
   * gone. It stays in the set because SPEC §5 names it and because a budget that reaps is a
   * thing this Runtime could acquire again — not because anything here reaches it.
   */
  @Serializable
  data class TimedOut(override val survivors: List<Survivor>) : Reaped

  /** The Operation was stopped deliberately: the frontend's stop control, or a Runtime Stop. */
  @Serializable
  data class Stopped(override val survivors: List<Survivor>) : Reaped

  @Serializable
  data class RootBrokeMidOperation(val workspace: String) : Uncertainty

  /**
   * The call carrying the Operation ended before the Operation did — it was cancelled, or the
   * Runtime hit a defect partway. Only a repeat Delivery of the same key ever reads this, since
   * the call it belonged to was not there to be answered.
   */
  @Serializable
  data object Interrupted : Uncertainty

  /**
   * A repeat Delivery waited on the first, which was still running when the repeat's own
   * budget ran out (§6.4). Never a `failed`: the first is changing the disk at that moment, so
   * "nothing changed" would be a lie.
   */
  @Serializable
  data class FirstDeliveryInFlight(val key: String) : Uncertainty
}
