package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.PatternSyntaxException
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration

private const val GIT_DIRECTORY = ".git"

/**
 * Filename and content in one pass (SPEC §4), entirely in this JVM. Not delegated to ripgrep:
 * one code path, and no assumption about what the host has installed.
 *
 * The decision worth keeping is **how it enumerates**. Inside a repository it asks Git —
 * `git ls-files --cached --others --exclude-standard`, tracked plus untracked-not-ignored —
 * which gets the project's real ignore rules for free instead of reimplementing `.gitignore`.
 * Outside one, a plain walk skipping `.git/`. A bare walk was rejected because `node_modules`,
 * `build/` and vendored dependencies fill the cap before reaching the user's own code, and a
 * hardcoded skip list is wrong for somebody's project by definition.
 *
 * Every way it can return less — the result cap, the time budget, the byte bound — is an `ok`
 * with less in it. A search is read-only and capped by design, so there is nothing about a
 * short answer that is [Outcome.Uncertain], and nothing here is ever Promoted (§6.2).
 */
internal fun search(
  op: Operation.Search,
  workspace: Workspace,
  subtree: Path,
  budget: Duration,
): Outcome<SearchResults> {
  if (op.query.isEmpty()) return Outcome.Failed(
    Failure.InvalidArgument("query"),
    "A search needs something to look for, and the query is empty.",
  )
  notADirectory(workspace, op.label, subtree)?.let { return it }
  val matcher = try {
    Matcher(op)
  } catch (invalid: PatternSyntaxException) {
    return Outcome.Failed(
      Failure.InvalidArgument("query"),
      "'${op.query}' is not a usable regular expression: ${invalid.description}.",
    )
  }

  // Resolved again here rather than carried from confinement, and a failure means the same
  // thing it means there: the Root has moved out from under the registration.
  val rootReal = try {
    Path.of(workspace.root).toRealPath()
  } catch (_: IOException) {
    return Outcome.Failed(
      Failure.WorkspaceBroken(workspace.name),
      "Workspace '${workspace.name}' is Broken; re-confirm its Root.",
    )
  }
  val deadline = Deadline(budget)
  val scan = Scan(matcher, rootReal, deadline)
  val enumeration = enumerate(subtree, deadline, scan) ?: return gitUnavailable(workspace, op.label, subtree)

  val bounded = headAndTail(scan.hits, Operation.OUTPUT_HEAD_BYTES, Operation.OUTPUT_TAIL_BYTES) { it.weight() }
  return Outcome.Ok(
    SearchResults(
      op.label,
      enumeration,
      bounded.kept,
      scan.filesSearched,
      SearchBounds(
        scan.cappedByResults,
        scan.cappedByTime,
        bounded.droppedBytes,
        bounded.droppedItems,
        scan.linesNotFullySearched,
      ),
    ),
  )
}

/**
 * The repository is there and its ignore rules are not. Refused in the words that name the
 * remedy, because the alternative — walking it anyway — hands back the files a `.gitignore`
 * exists to keep out of a conversation that leaves this machine.
 */
private fun gitUnavailable(workspace: Workspace, label: String, subtree: Path): Outcome.Failed {
  val repository = enclosingGitRepository(subtree)?.toString() ?: subtree.toString()
  return Outcome.Failed(
    Failure.GitUnavailable(workspace.name, repository),
    "'$label' is inside the Git repository at '$repository', and this search could not read " +
      "that repository's ignore rules — 'git' is missing, unusable, or the repository is " +
      "broken. Searching without them would return files the project ignores, so nothing was " +
      "searched. Install or repair 'git' and try again.",
  )
}

/**
 * Hands each candidate file to [scan], which stops accepting them once nothing more is kept.
 * Null is the one situation neither enumeration may answer: a repository whose ignore rules
 * could not be read, where walking would return the very files the project ignores.
 */
private fun enumerate(subtree: Path, deadline: Deadline, scan: Scan): Enumeration? {
  // Asking Git costs a process, and a budget already spent cannot pay for one. The walk below
  // still runs and stops on its first candidate, so what is reported is what was done.
  if (deadline.spent()) {
    scan.markTimeSpent()
  } else when (gitEnumerate(subtree, deadline, scan)) {
    GitOutcome.Enumerated -> return Enumeration.GitIgnoreRules
    GitOutcome.Unusable -> return null
    // Git could not speak for this subtree. Whether that is because there is no repository or
    // because there is one we cannot read is a question Git's own layout answers, and the two
    // must not be conflated: one is an ordinary walk, the other is a refusal.
    GitOutcome.Silent -> if (enclosingGitRepository(subtree) != null) return null
  }
  walk(subtree, deadline, scan)
  return Enumeration.PlainWalk
}

/** What asking Git got us. */
private enum class GitOutcome {
  /** Git listed the subtree and ended cleanly, or we stopped it ourselves. */
  Enumerated,

  /** Git listed part of the subtree and then failed: what it gave cannot be trusted as whole. */
  Unusable,

  /** Git listed nothing and failed — "not a repository", or no `git` to ask at all. */
  Silent,
}

/**
 * Ask Git for the files it does not ignore. Started in the subtree, because `git ls-files`
 * limits itself to the directory it is run from and prints paths relative to it. That is the §4
 * scoping of a Root nested inside a larger repository, arrived at without a pathspec anybody
 * can get wrong.
 */
private fun gitEnumerate(subtree: Path, deadline: Deadline, scan: Scan): GitOutcome {
  val builder = ProcessBuilder(
    "git", "--no-optional-locks", "ls-files", "-z", "--cached", "--others", "--exclude-standard",
  ).directory(subtree.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
  // §4: a repository needing credentials fails instead of hanging on a prompt nobody can see.
  builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
  val process = try {
    builder.start()
  } catch (_: IOException) {
    return GitOutcome.Silent // No `git` on this machine.
  }

  // Reading Git's output is the one blocking call the deadline cannot reach into: a child that
  // holds stdout open without writing leaves `read` parked forever. So the budget is enforced
  // from outside, by killing the child — which closes the pipe and ends the read at EOF.
  val killed = AtomicBoolean(false)
  val watchdog = Thread.ofVirtual().start {
    try {
      if (!process.waitFor(deadline.remainingMillis(), MILLISECONDS)) {
        killed.set(true)
        process.destroyForcibly()
      }
    } catch (_: InterruptedException) {
      // The drain finished first and no longer needs watching.
    }
  }

  var enumerated = false
  var stopped = false
  try {
    // Streamed rather than collected: a monorepo's file list is megabytes, and the caller may
    // have stopped wanting it after the first two hundred hits anyway.
    process.inputStream.buffered().use { input ->
      val name = ByteArrayOutputStream()
      while (!stopped) {
        val byte = input.read()
        if (byte < 0) break
        // -z, so a filename with a newline in it is still one name.
        if (byte != 0) {
          name.write(byte)
          continue
        }
        enumerated = true
        val relative = String(name.toByteArray(), UTF_8)
        name.reset()
        stopped = !scan.visit(subtree.resolve(relative))
      }
    }
  } catch (_: IOException) {
    // The pipe died under us, so what Git had still to say is lost with it.
    if (!killed.get()) return finish(process, watchdog, GitOutcome.Unusable)
  }
  if (killed.get()) {
    scan.markTimeSpent()
    // Cut by the budget, not by Git: what it listed before the kill is Git's own answer, and
    // the time marker already says the rest was not reached.
    return finish(process, watchdog, GitOutcome.Enumerated)
  }
  if (stopped) return finish(process, watchdog, GitOutcome.Enumerated)

  val finished = process.waitFor(deadline.remainingMillis(), MILLISECONDS)
  if (!finished) {
    scan.markTimeSpent()
    return finish(process, watchdog, GitOutcome.Enumerated)
  }
  if (process.exitValue() == 0) return finish(process, watchdog, GitOutcome.Enumerated)
  // Listed some of the subtree and then failed. The file set it gave is not the file set the
  // ignore rules describe, and a short answer presented as a whole one is the worst of both.
  return finish(process, watchdog, if (enumerated) GitOutcome.Unusable else GitOutcome.Silent)
}

private fun finish(process: Process, watchdog: Thread, outcome: GitOutcome): GitOutcome {
  process.destroy()
  watchdog.interrupt()
  return outcome
}

/** No repository to ask, so every file under the subtree except Git's own metadata. */
private fun walk(subtree: Path, deadline: Deadline, scan: Scan) {
  Files.walkFileTree(
    subtree,
    object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = when {
        // Checked here as well as at each file: a tree of empty directories costs time and
        // offers nothing to check it against, and the budget is a bound or it is nothing.
        deadline.spent() -> {
          scan.markTimeSpent()
          FileVisitResult.TERMINATE
        }
        dir != subtree && dir.fileName?.toString() == GIT_DIRECTORY -> FileVisitResult.SKIP_SUBTREE
        else -> FileVisitResult.CONTINUE
      }

      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (scan.visit(file)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

      // Symlinks are not followed here, so a loop is not a hang; an unreadable directory is
      // skipped rather than failing a search of everything around it.
      override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
    },
  )
}

/** What the matching costs, kept in one place so that the flags cannot disagree between uses. */
private class Matcher(op: Operation.Search) {
  private val query = op.query
  private val ignoreCase = !op.caseSensitive

  // Compiled once. Constructed eagerly on purpose: an unusable expression is an argument
  // problem, and the caller hears about it instead of getting an empty result set.
  private val expression =
    if (!op.regex) null
    else Regex(op.query, if (op.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

  /**
   * A regular expression is the one piece of work here with no loop of our own to check a
   * deadline in: backtracking happens entirely inside the matcher. So the text is handed over
   * through [Deadlined], which stops feeding it characters once the budget is gone.
   *
   * Insurance rather than a fix for anything measured. The classic catastrophic patterns were
   * tried against this JDK and all returned in single-digit milliseconds, so there is no test
   * below that fails without this — it is here because the cost is a clock read per kilobyte
   * and the alternative, on a JDK that optimises less, is a Runtime that stops answering.
   */
  fun matches(text: String, deadline: Deadline): Boolean = when (val regex = expression) {
    null -> text.contains(query, ignoreCase)
    else -> regex.containsMatchIn(Deadlined(text, deadline))
  }
}

/** Thrown out of a regex that outran the budget, and caught by the scan that started it. */
private class BudgetSpent : RuntimeException(null, null, false, false)

/**
 * The text a regex walks, which gives up when the budget does. Checked every
 * [CHECK_INTERVAL] characters rather than every one, because the check is a clock read and
 * backtracking touches a character many times over.
 */
private class Deadlined(private val text: String, private val deadline: Deadline) : CharSequence {
  private var reads = 0

  override val length: Int get() = text.length

  override fun get(index: Int): Char {
    if (reads++ % CHECK_INTERVAL == 0 && deadline.spent()) throw BudgetSpent()
    return text[index]
  }

  override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
    Deadlined(text.substring(startIndex, endIndex), deadline)

  override fun toString(): String = text

  private companion object {
    const val CHECK_INTERVAL = 1024
  }
}

/** The time bound, read from the monotonic clock so that a clock adjustment cannot extend it. */
private class Deadline(budget: Duration) {
  private val at = System.nanoTime() + budget.inWholeNanoseconds

  fun spent(): Boolean = System.nanoTime() - at >= 0

  fun remainingMillis(): Long = maxOf(0L, (at - System.nanoTime()) / 1_000_000)
}

/** The scan itself: what was enumerated, what matched, and every bound that bit while it ran. */
private class Scan(
  private val matcher: Matcher,
  private val rootReal: Path,
  private val deadline: Deadline,
) {
  val hits = mutableListOf<SearchHit>()
  var filesSearched = 0
    private set
  var cappedByResults = false
    private set
  var cappedByTime = false
    private set
  var linesNotFullySearched = 0
    private set

  /** False once nothing more will be recorded, which is what stops the enumeration. */
  fun visit(file: Path): Boolean {
    if (deadline.spent()) {
      cappedByTime = true
      return false
    }
    val real = try {
      file.toRealPath()
    } catch (_: IOException) {
      return true // A dangling link, or a file deleted while the search was running.
    }
    // §4: symlinks leaving the Root are skipped rather than followed. The path reported is the
    // one that was enumerated, not the target's: that is the path the caller can read back.
    if (!real.startsWith(rootReal)) return true
    if (!Files.isRegularFile(real)) return true

    val relative = rootReal.relativize(file).toString()
    filesSearched++
    // Held aside and only then kept, because a file is binary the moment a NUL appears anywhere
    // in it — including after lines that already matched.
    val cappedBefore = cappedByResults
    val found = mutableListOf<SearchHit>()
    if (matches(relative)) record(found, SearchHit.Name(relative))
    if (scanContents(file, relative, found)) {
      hits += found
    } else {
      // The contents go; the name stays, because it matched on its own terms — §4 skips a
      // binary file's *contents*, and `vendor/needle.png` is still where the model asked to be
      // pointed. The cap the dropped lines spent is given back with them, so that a binary
      // file is never what ends a search early.
      cappedByResults = cappedBefore
      hits += found.filterIsInstance<SearchHit.Name>()
    }
    return !cappedByResults && !cappedByTime
  }

  /** The budget ran out somewhere the scan itself could not see it, such as inside Git. */
  fun markTimeSpent() {
    cappedByTime = true
  }

  /** Matching, with a regex that outran the budget turned into the marker it deserves. */
  private fun matches(text: String): Boolean = try {
    matcher.matches(text, deadline)
  } catch (_: BudgetSpent) {
    cappedByTime = true
    false
  }

  /** Into [pending], which is held apart from [hits] until the file it came from is kept. */
  private fun record(pending: MutableList<SearchHit>, hit: SearchHit) {
    // Set only when a hit is actually turned away: a search that found exactly the cap and no
    // more is complete, and saying otherwise would send the caller looking for nothing.
    if (hits.size + pending.size >= Operation.SEARCH_RESULT_CAP) {
      cappedByResults = true
      return
    }
    pending += hit
  }

  /** False when this file's hits are to be dropped with it: it is binary, or time ran out. */
  private fun scanContents(file: Path, relative: String, into: MutableList<SearchHit>): Boolean {
    var lineNumber = 0
    val line = ByteArrayOutputStream()
    var overflowed = false

    fun endLine() {
      lineNumber++
      if (line.size() == 0 && !overflowed) return
      // A line past the cap was matched on only as much of it as was held, so a miss here is
      // not proof the query is absent from it — counted, never silent, because a minified
      // bundle is exactly the file a model would otherwise conclude nothing about.
      if (overflowed) linesNotFullySearched++
      val decoded = String(line.toByteArray(), UTF_8).removeSuffix("\r")
      if (matches(decoded)) {
        val quoted = decoded.takeUtf8(Operation.SEARCH_LINE_CAP_BYTES)
        record(into, SearchHit.Content(relative, lineNumber, quoted, quoted.length < decoded.length))
      }
      line.reset()
      overflowed = false
    }

    // Byte-oriented like a read (§4): 0x0A never appears inside a multi-byte UTF-8 sequence,
    // so lines are cut without first decoding a file that may not decode.
    Files.newInputStream(file).use { input ->
      val chunk = ByteArray(1 shl 16)
      while (true) {
        if (deadline.spent()) {
          cappedByTime = true
          return false
        }
        val read = input.read(chunk)
        if (read < 0) break
        for (index in 0 until read) {
          val byte = chunk[index]
          if (byte == ZERO) return false
          when {
            byte == NEWLINE -> endLine()
            // Past the cap nothing more is recorded, but the file is still read to the end:
            // a NUL after the two hundredth hit makes it binary just the same.
            cappedByResults -> Unit
            line.size() < Operation.OUTPUT_CAP_BYTES -> line.write(byte.toInt())
            else -> overflowed = true
          }
        }
      }
    }
    if (line.size() > 0 || overflowed) endLine()
    return true
  }
}

/**
 * What this hit costs the reply: every string it carries that varies. Head and tail rather than
 * head alone because a search is enumeration-ordered — the two ends are two places in the
 * project, and one end of them would be an answer about one directory.
 */
private fun SearchHit.weight(): Int = path.utf8Size() +
  if (this is SearchHit.Content) text.utf8Size() + line.toString().length else 0
