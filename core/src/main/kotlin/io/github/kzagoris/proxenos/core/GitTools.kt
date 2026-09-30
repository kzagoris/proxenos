package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration

private const val GIT_PROGRAM = "git"

/** Pathspec magic, so a Root whose name holds `*` or `:` is a path rather than a pattern. */
private const val LITERAL = ":(literal)"

/** Between a commit's fields. Unit separator: not a byte a subject or a name can hold. */
private const val FIELD = '\u001f'

/**
 * The user's own `git` config, overruled where it would change what these tools mean. Paths
 * unquoted so `-z` output is bytes rather than C escapes, and a patch that looks like every
 * other patch — `a/` and `b/` — whatever this user has set for their own reading.
 */
private val CONFIG = listOf(
  "-c", "core.quotePath=false",
  "-c", "diff.mnemonicPrefix=false",
  "-c", "diff.noprefix=false",
)

/** What of Git's complaint is kept to put in front of the model. */
private const val COMPLAINT_CAP_BYTES = 4 * 1024

/**
 * The three read-only Git tools: `git_status`, `git_diff` and `git_log`, at Read, so a
 * Workspace can show what changed without granting command execution.
 *
 * Two decisions are worth keeping. The first is **scoping**: where the Root sits inside a larger
 * repository the invocation runs at the repository root and is pathspec-scoped to the Root, and
 * every answer carries [GitScope] saying so — an empty `git_status` that did not say it had been
 * scoped would be read as "the repository is clean", which is the whole reason the label exists.
 *
 * The second is that `git` is **optional**. Whether a Root is a repository is read from
 * Git's own on-disk layout ([enclosingGitRepository]), so the catalog entry is there and answers
 * whether or not the program is installed; a machine without it takes the same plain `failed`
 * path as a Root with no repository above it.
 */

/**
 * Starting `git`, and the one place its two mandatory arguments live. `--no-optional-locks` so
 * that a read-only tool of ours never contends with the user's own `git`, and
 * `GIT_TERMINAL_PROMPT=0` so that a repository wanting credentials fails instead of hanging on
 * a prompt nobody can see. Both are here rather than at three call sites, because the third
 * call site is the one that would forget them.
 *
 * [program] and [budget] are injected for the same reason the search budget is: a test asks
 * what happens with no usable `git`, or with the budget already gone, instead of uninstalling
 * Git from the machine running it.
 */
internal class GitTools(
  private val program: String = GIT_PROGRAM,
  private val budget: Duration = Operation.GIT_BUDGET,
) {
  /** The invocation, before it is started. Internal so a test can read what is really sent. */
  fun builder(directory: Path, arguments: List<String>): ProcessBuilder {
    val builder = ProcessBuilder(
      listOf(program, "--no-optional-locks", "-C", directory.toString()) + CONFIG + arguments,
    ).directory(directory.toFile())
    builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
    return builder
  }

  /**
   * One invocation, with [read] draining standard output on this thread. Standard error is
   * drained concurrently rather than after: a child whose error pipe fills while nobody is
   * reading it stops writing, and the read of its output would then never end.
   */
  fun <T : Any> run(directory: Path, arguments: List<String>, read: (InputStream) -> T): GitRun<T> {
    val process = try {
      builder(directory, arguments).start()
    } catch (_: IOException) {
      return GitRun.Unstartable // No `git` on this machine.
    }

    val complaint = StringBuilder()
    val draining = Thread.ofVirtual().start {
      try {
        // Drained whole, kept in part: closing the pipe early would hand the child a SIGPIPE
        // and turn a wordy warning into a failure of the read it was only commenting on.
        process.errorStream.use { errors ->
          val chunk = ByteArray(8 * 1024)
          while (true) {
            val read = errors.read(chunk)
            if (read < 0) break
            val room = COMPLAINT_CAP_BYTES - complaint.length
            if (room > 0) complaint.append(String(chunk, 0, minOf(read, room), UTF_8))
          }
        }
      } catch (_: IOException) {
        // What Git had still to complain about is lost; the exit code still says it failed.
      }
    }
    // The read below is the one blocking call no deadline can reach into, so the budget is
    // enforced from outside by killing the child — which closes the pipe and ends the read.
    val killed = AtomicBoolean(false)
    val watchdog = Thread.ofVirtual().start {
      try {
        if (!process.waitFor(budget.inWholeMilliseconds, MILLISECONDS)) {
          killed.set(true)
          process.destroyForcibly()
        }
      } catch (_: InterruptedException) {
        // The read finished first and the child no longer needs watching.
      }
    }

    var value: T? = null
    try {
      value = process.inputStream.buffered().use(read)
    } catch (_: IOException) {
      // The pipe died under us. Whether that was the watchdog or the child is settled below.
    }
    val exit = process.waitFor()
    watchdog.interrupt()
    draining.join()

    return when {
      killed.get() -> GitRun.TimedOut
      exit != 0 -> GitRun.Refused(complaint.toString().trim().ifEmpty { "git exited with status $exit" })
      value == null -> GitRun.Refused("git's output could not be read")
      else -> GitRun.Ok(value)
    }
  }
}

/** What one invocation got us. Three of the four are the same plain `failed` to a caller. */
internal sealed interface GitRun<out T> {
  data class Ok<T : Any>(val value: T) : GitRun<T>

  /** Every way asking Git can fail. One plain `failed` to a caller, whichever of the three. */
  sealed interface Problem : GitRun<Nothing>

  /** Git ran and exited non-zero, carrying its own complaint — which is better than ours. */
  data class Refused(val complaint: String) : Problem

  /** There is no usable `git` on this machine. */
  data object Unstartable : Problem

  /** [Operation.GIT_BUDGET] ran out and the child was killed, so its output is a fragment. */
  data object TimedOut : Problem
}

/**
 * `git status`, parsed from porcelain rather than from the human-readable form: the porcelain
 * format is the one Git promises not to change, and `-z` is what keeps a filename with a
 * newline in it one filename.
 */
internal fun gitStatus(workspace: Workspace, root: Path, git: GitTools): Outcome<GitStatusReport> {
  val scope = scopeOf(workspace, root).valueOr { return it }
  val entries = git.run(
    Path.of(scope.repository),
    // `--untracked-files=normal` is stated rather than left to `status.showUntrackedFiles`,
    // which a user may have turned off for their own prompt and did not mean to turn off here.
    listOf("status", "--porcelain", "-z", "--untracked-files=normal") + scope.pathspec(),
  ) { input -> readStatus(input, scope) }.valueOr { return gitFailed(workspace, scope, it) }

  val bounded = headAndTail(entries, Operation.OUTPUT_HEAD_BYTES, Operation.OUTPUT_TAIL_BYTES) { it.weight() }
  return Outcome.Ok(GitStatusReport(scope, bounded.kept, entries.size, bounded.droppedBytes))
}

/**
 * `git diff`: the working tree against `HEAD`, staged and unstaged together, unless `staged`
 * narrows it to what is staged alone.
 *
 * `--no-ext-diff` and `--no-textconv` are not tidiness. Both are configuration that makes `git
 * diff` run a program of the user's choosing, and a read-only tool at Read must not become a
 * way to run one.
 */
internal fun gitDiff(op: Operation.GitDiff, workspace: Workspace, root: Path, git: GitTools): Outcome<GitDiffReport> {
  val scope = scopeOf(workspace, root).valueOr { return it }
  val arguments = listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv") +
    (if (op.staged) listOf("--staged") else listOf("HEAD")) + scope.pathspec()
  val patch = git.run(Path.of(scope.repository), arguments) { input ->
    HeadAndTailBytes(Operation.OUTPUT_HEAD_BYTES, Operation.OUTPUT_TAIL_BYTES).drain(input)
  }.valueOr { return gitFailed(workspace, scope, it) }

  return Outcome.Ok(GitDiffReport(scope, op.staged, patch.text, patch.droppedBytes))
}

/**
 * `git log`: the most recent commits with hash, subject, author and date. Cut at the old end
 * rather than in the middle when the byte cap binds — a log is read from the recent end, and a
 * hole in the middle of one is not something a caller can ask past.
 */
internal fun gitLog(op: Operation.GitLog, workspace: Workspace, root: Path, git: GitTools): Outcome<GitLogReport> {
  if ((op.limit ?: 1) < 1) return Outcome.Failed(
    Failure.InvalidArgument("limit"), "limit is a number of commits, so it cannot be ${op.limit}.",
  )
  val scope = scopeOf(workspace, root).valueOr { return it }
  val commits = git.run(
    Path.of(scope.repository),
    listOf(
      "log", "--no-color", "-z", "-n", (op.limit ?: Operation.GIT_LOG_COMMITS).toString(),
      // %aI is strict ISO-8601 in the author's own offset, which is the date they wrote it at.
      "--format=%H$FIELD%an$FIELD%aI$FIELD%s",
    ) + scope.pathspec(),
  ) { input -> readLog(input) }.valueOr { return gitFailed(workspace, scope, it) }

  // A tail of nothing, which is the whole of "cut at the old end" — the same bound the other
  // two tools take, rather than a third piece of arithmetic to keep in step with them.
  val bounded = headAndTail(commits, Operation.OUTPUT_CAP_BYTES, 0) { it.weight() }
  return Outcome.Ok(GitLogReport(scope, bounded.kept, commits.size, bounded.droppedBytes))
}

/**
 * The repository the Root sits in, and how much of it this Root may speak for. No repository is
 * the plain error the tool answers with; it is read from Git's own layout rather than by asking
 * the program, so it is the same answer on a machine with no `git` installed.
 */
private fun scopeOf(workspace: Workspace, root: Path): Outcome<GitScope> {
  val repository = enclosingGitRepository(root) ?: return Outcome.Failed(
    Failure.NoRepository(workspace.name, workspace.root),
    "The Root of Workspace '${workspace.name}' is not inside a Git repository, so there is " +
      "nothing for this tool to read.",
  )
  val scopedTo = if (repository == root) null else repository.relativize(root).toString()
  return Outcome.Ok(GitScope(repository.toString(), scopedTo))
}

/** Scoped to the Root, or the whole repository when the Root is its root. */
private fun GitScope.pathspec(): List<String> = scopedTo?.let { listOf("--", "$LITERAL$it") } ?: emptyList()

/**
 * Every way asking Git can fail, in one plain `failed`. Never [Outcome.Uncertain]: these tools
 * read, so a failure of one guarantees nothing on disk changed.
 */
private fun gitFailed(workspace: Workspace, scope: GitScope, run: GitRun.Problem): Outcome.Failed {
  val detail = when (run) {
    is GitRun.Refused -> "git refused: ${run.complaint}"
    GitRun.Unstartable -> "'git' is not installed on this machine, or could not be started"
    GitRun.TimedOut -> "git did not answer within ${Operation.GIT_BUDGET}"
  }
  return Outcome.Failed(
    Failure.GitUnavailable(workspace.name, scope.repository),
    "This Operation failed and changed nothing. The repository at '${scope.repository}' could " +
      "not be read: $detail.",
  )
}

/** Carries the three failures out, keeping each tool above to one line per step. */
private inline fun <T : Any> GitRun<T>.valueOr(onProblem: (GitRun.Problem) -> Nothing): T = when (this) {
  is GitRun.Ok -> value
  is GitRun.Problem -> onProblem(this)
}

/**
 * Porcelain v1 with `-z`: each record is `XY <path>` NUL, and a rename or copy is followed by a
 * second field holding where it came from. The `-z` form reverses the human format's order — it
 * is the new path first and the original second — and performs no quoting, which is why it is
 * the form parsed here.
 */
private fun readStatus(input: InputStream, scope: GitScope): List<GitStatusEntry> {
  val fields = readNulTerminated(input)
  val entries = mutableListOf<GitStatusEntry>()
  var index = 0
  while (index < fields.size) {
    val record = fields[index++]
    // A record is two status characters, a space, then the path. Anything shorter is not one.
    if (record.length < 4) continue
    val staged = record[0]
    val workTree = record[1]
    val path = record.substring(3)
    val renamed = staged in RENAMED || workTree in RENAMED
    val original = if (renamed && index < fields.size) fields[index++] else null
    entries += GitStatusEntry(
      scope.rootRelative(path), staged, workTree, original?.let { scope.rootRelative(it) },
    )
  }
  return entries
}

private const val RENAMED = "RC"

/** `hash`, author, date, subject, NUL-terminated by `-z`. The subject is last, so it keeps any
 * separator it happens to contain rather than being cut at one. */
private fun readLog(input: InputStream): List<GitCommit> = readNulTerminated(input).mapNotNull { record ->
  val fields = record.trimStart('\n').split(FIELD, limit = 4)
  if (fields.size < 4) null else GitCommit(fields[0], fields[3], fields[1], fields[2])
}

/** The one NUL-splitting read both parsers share. A filename may hold anything but NUL. */
private fun readNulTerminated(input: InputStream): List<String> {
  val fields = mutableListOf<String>()
  val field = ByteArrayOutputStream()
  while (true) {
    val byte = input.read()
    if (byte < 0) break
    if (byte == 0) {
      fields += String(field.toByteArray(), UTF_8)
      field.reset()
    } else {
      field.write(byte)
    }
  }
  // Git terminates every record, so a remainder is a record it was killed part-way through.
  if (field.size() > 0) fields += String(field.toByteArray(), UTF_8)
  return fields
}

/**
 * Porcelain prints paths relative to the repository root. Where the tools are scoped, they are
 * re-based on the Root so that what comes back is a path `read_file` can be called with.
 *
 * A rename whose other end lies outside the Root has no Root-relative form, and is left as Git
 * gave it rather than turned into a `../` the file tools would refuse.
 */
private fun GitScope.rootRelative(path: String): String {
  val prefix = scopedTo?.plus("/") ?: return path
  return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
}

private fun GitStatusEntry.weight(): Int = path.utf8Size() + (originalPath?.utf8Size() ?: 0) + 2

private fun GitCommit.weight(): Int = hash.length + subject.utf8Size() + author.utf8Size() + date.length
