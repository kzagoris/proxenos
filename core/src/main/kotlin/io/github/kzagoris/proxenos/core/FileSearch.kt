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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val GIT_DIRECTORY = ".git"

/**
 * Filename and content in one pass, entirely in this JVM. Not delegated to ripgrep:
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
 * short answer that is [Outcome.Uncertain], and nothing here is ever Promoted.
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
  val invalidField = when {
    op.mode !in setOf("all", "filename", "content") -> "mode"
    op.contextBefore !in 0..10 -> "context_before"
    op.contextAfter !in 0..10 -> "context_after"
    op.limit !in 1..Operation.SEARCH_RESULT_CAP -> "limit"
    else -> null
  }
  if (invalidField != null) return Outcome.Failed(
    Failure.InvalidArgument(invalidField), "Invalid search option '$invalidField'.",
  )
  val filters = try {
    Filters(op.includeGlobs, op.excludeGlobs)
  } catch (invalid: InvalidGlob) {
    return Outcome.Failed(Failure.InvalidArgument(invalid.field), "Invalid search glob in '${invalid.field}'.")
  }
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
  val scan = Scan(op, matcher, filters, rootReal, deadline)
  val enumeration = enumerate(subtree, deadline, scan) ?: return gitUnavailable(workspace, op.label, subtree)
  return Outcome.Ok(boundedResults(op, enumeration, scan))
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
 * limits itself to the directory it is run from and prints paths relative to it. That is the
 * scoping of a Root nested inside a larger repository, arrived at without a pathspec anybody
 * can get wrong.
 */
private fun gitEnumerate(subtree: Path, deadline: Deadline, scan: Scan): GitOutcome {
  val builder = ProcessBuilder(
    "git", "--no-optional-locks", "ls-files", "-z", "--cached", "--others", "--exclude-standard",
  ).directory(subtree.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
  // A repository needing credentials fails instead of hanging on a prompt nobody can see.
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

private class InvalidGlob(val field: String) : RuntimeException()

private class Filters(includes: List<String>, excludes: List<String>) {
  private val includes = includes.map { compile(it, "include_globs") }
  private val excludes = excludes.map { compile(it, "exclude_globs") }

  fun accepts(path: String, deadline: Deadline): Boolean =
    (includes.isEmpty() || includes.any { it.matches(Deadlined(path, deadline)) }) &&
      excludes.none { it.matches(Deadlined(path, deadline)) }

  private fun compile(glob: String, field: String): Regex {
    if (glob.isEmpty() || glob.startsWith('/') || glob.any { it in "[]{}\\\u0000" } ||
      glob.split('/').any { it == "." || it == ".." } || "***" in glob
    ) throw InvalidGlob(field)
    val pattern = StringBuilder("^")
    var index = 0
    while (index < glob.length) {
      when (val character = glob[index]) {
        '*' -> if (index + 1 < glob.length && glob[index + 1] == '*') {
          index++
          if (index + 1 < glob.length && glob[index + 1] == '/') {
            pattern.append("(?:.*/)?")
            index++
          } else pattern.append(".*")
        } else pattern.append("[^/]*")
        '?' -> pattern.append("[^/]")
        else -> pattern.append(Regex.escape(character.toString()))
      }
      index++
    }
    return Regex(pattern.append('$').toString(), RegexOption.DOT_MATCHES_ALL)
  }
}

/** The scan itself: what was enumerated, what matched, and every bound that bit while it ran. */
private class Scan(
  private val op: Operation.Search,
  private val matcher: Matcher,
  private val filters: Filters,
  private val rootReal: Path,
  private val deadline: Deadline,
) {
  val hits = mutableListOf<SearchHit>()
  val windows = mutableListOf<SearchWindow>()
  var filesSearched = 0
    private set
  var cappedByResults = false
    private set
  var cappedByTime = false
    private set
  var linesNotFullySearched = 0
    private set
  var contextTruncated = false
    private set
  private val wantsContext = op.contextBefore > 0 || op.contextAfter > 0

  fun visit(file: Path): Boolean {
    if (deadline.spent()) {
      cappedByTime = true
      return false
    }
    val real = try {
      file.toRealPath()
    } catch (_: IOException) {
      return true
    }
    if (!real.startsWith(rootReal) || !Files.isRegularFile(real)) return true
    val relative = rootReal.relativize(file).toString().replace(file.fileSystem.separator, "/")
    try {
      if (!filters.accepts(relative, deadline)) return true
    } catch (_: BudgetSpent) {
      cappedByTime = true
      return false
    }
    filesSearched++
    val contextBefore = contextTruncated
    val found = mutableListOf<SearchHit>()
    if (op.mode != "content" && matches(relative)) record(found, SearchHit.Name(relative))
    val nameCapped = cappedByResults
    if (op.mode == "filename") {
      hits += found
    } else {
      val context = scanContents(file, relative, found)
      if (context != null) {
        hits += found
        windows += context
      } else {
        // A NUL anywhere makes the whole content binary, even after a matching prefix.
        cappedByResults = nameCapped
        if (!cappedByTime) contextTruncated = contextBefore
        hits += found.filterIsInstance<SearchHit.Name>()
      }
    }
    return !cappedByResults && !cappedByTime
  }

  fun markTimeSpent() {
    cappedByTime = true
  }

  private fun matches(text: String): Boolean = try {
    matcher.matches(text, deadline)
  } catch (_: BudgetSpent) {
    cappedByTime = true
    false
  }

  private fun record(pending: MutableList<SearchHit>, hit: SearchHit): Boolean {
    if (hits.size + pending.size >= op.limit) {
      cappedByResults = true
      return false
    }
    pending += hit
    return true
  }

  private fun scanContents(file: Path, relative: String, into: MutableList<SearchHit>): List<SearchWindow>? {
    var lineNumber = 0
    val line = ByteArrayOutputStream()
    var overflowed = false
    var afterUntil = 0
    var contextStopped = false
    val before = ArrayDeque<SearchLine>()
    val groups = mutableListOf<MutableList<SearchLine>>()

    fun append(target: MutableList<SearchLine>, value: SearchLine) {
      if (target.lastOrNull()?.line != value.line) {
        target += value
        if (value.lineCut) contextTruncated = true
      }
    }

    fun endLine() {
      lineNumber++
      if (overflowed) linesNotFullySearched++
      val decoded = String(line.toByteArray(), UTF_8).removeSuffix("\r")
      val text = decoded.takeUtf8(Operation.SEARCH_LINE_CAP_BYTES)
      val matched = !cappedByResults && matches(decoded)
      val accepted = matched && record(into, SearchHit.Content(relative, lineNumber, text, text.length < decoded.length))
      val value = SearchLine(lineNumber, text, accepted, text.length < decoded.length)
      if (wantsContext && !contextStopped) {
        if (matched && !accepted) {
          if (lineNumber <= afterUntil) contextTruncated = true
          contextStopped = true
        } else if (accepted) {
          val previous = groups.lastOrNull()
          val target = if (previous != null && previous.last().line >= lineNumber - op.contextBefore - 1) previous
          else mutableListOf<SearchLine>().also { groups += it }
          for (earlier in before) if (earlier.line > (target.lastOrNull()?.line ?: 0)) append(target, earlier)
          append(target, value)
          afterUntil = lineNumber + op.contextAfter
        } else if (lineNumber <= afterUntil) append(groups.last(), value)
        before += value
        if (before.size > op.contextBefore) before.removeFirst()
      }
      line.reset()
      overflowed = false
    }

    Files.newInputStream(file).use { input ->
      val chunk = ByteArray(1 shl 16)
      while (true) {
        if (deadline.spent() || cappedByTime) {
          cappedByTime = true
          if (wantsContext && groups.isNotEmpty()) contextTruncated = true
          return null
        }
        val read = input.read(chunk)
        if (read < 0) break
        for (index in 0 until read) {
          val byte = chunk[index]
          if (byte == ZERO) return null
          // Keep draining after the limit: a later NUL still invalidates all content hits.
          if (cappedByResults && (contextStopped || lineNumber >= afterUntil)) continue
          when {
            byte == NEWLINE -> endLine()
            line.size() < Operation.OUTPUT_CAP_BYTES -> line.write(byte.toInt())
            else -> overflowed = true
          }
        }
      }
    }
    if (line.size() > 0 || overflowed) endLine()
    return groups.map { SearchWindow(relative, it) }
  }
}

private const val SEARCH_REPLY_BYTES = 12 * 1024

/** Include JSON escaping and both hit/context representations in the bound. */
private fun boundedResults(op: Operation.Search, enumeration: Enumeration, scan: Scan): SearchResults {
  fun windowsFor(hits: List<SearchHit>, context: Set<Pair<String, Int>>? = null): List<SearchWindow> {
    val content = hits.filterIsInstance<SearchHit.Content>().groupBy { it.path }
    return scan.windows.flatMap { window ->
      val selected = content[window.path].orEmpty()
      val groups = mutableListOf<MutableList<SearchLine>>()
      for (line in window.lines) {
        val matched = selected.any { it.line == line.line }
        if (!matched && (line.matched || context != null && window.path to line.line !in context)) continue
        if (selected.none { line.line in (it.line - op.contextBefore)..(it.line + op.contextAfter) }) continue
        val previous = groups.lastOrNull()
        val target = if (previous != null && previous.last().line == line.line - 1) previous
        else mutableListOf<SearchLine>().also { groups += it }
        target += line
      }
      groups.map { SearchWindow(window.path, it) }
    }
  }

  val wantsContext = op.contextBefore > 0 || op.contextAfter > 0
  fun weight(hit: SearchHit): Int {
    val matchingLine = if (wantsContext && hit is SearchHit.Content) {
      Json.encodeToString(SearchLine(hit.line, hit.text, true, hit.lineCut)).utf8Size() +
        Json.encodeToString(hit.path).utf8Size() + 64
    } else 0
    return Json.encodeToString(SearchHit.serializer(), hit).utf8Size() + matchingLine + 2
  }

  val available = maxOf(0, SEARCH_REPLY_BYTES - Json.encodeToString(op.label).utf8Size() - 1024)
  val bounded = headAndTail(scan.hits, available / 2, available - available / 2, ::weight)
  fun result(windows: List<SearchWindow>, droppedBytes: Int, truncated: Boolean) = SearchResults(
    op.label, enumeration, bounded.kept, scan.filesSearched,
    SearchBounds(
      scan.cappedByResults, scan.cappedByTime, droppedBytes, bounded.droppedItems,
      scan.linesNotFullySearched, scan.contextTruncated || truncated,
    ),
    windows,
  )

  var windows = windowsFor(bounded.kept)
  if (Json.encodeToString(result(windows, 0, false)).utf8Size() > SEARCH_REPLY_BYTES - 128) {
    // Matching lines earn space first. Context is bounded separately, so broad context never
    // removes every otherwise useful hit merely because one window is too wide.
    val matchingOnly = windowsFor(bounded.kept, emptySet())
    val remaining = maxOf(0, SEARCH_REPLY_BYTES - 128 - Json.encodeToString(result(matchingOnly, 0, true)).utf8Size())
    val context = windows.flatMap { window -> window.lines.filterNot { it.matched }.map { window.path to it } }
    val retained = headAndTail(context, remaining / 2, remaining - remaining / 2) { (path, line) ->
      Json.encodeToString(line).utf8Size() + Json.encodeToString(path).utf8Size() + 64
    }.kept.map { (path, line) -> path to line.line }.toSet()
    windows = windowsFor(bounded.kept, retained)
  }
  val droppedBytes = Json.encodeToString(scan.hits).utf8Size() + Json.encodeToString(scan.windows).utf8Size() -
    Json.encodeToString(bounded.kept).utf8Size() - Json.encodeToString(windows).utf8Size()
  return result(
    windows, maxOf(0, droppedBytes),
    windows.sumOf { it.lines.size } < scan.windows.sumOf { it.lines.size },
  )
}
