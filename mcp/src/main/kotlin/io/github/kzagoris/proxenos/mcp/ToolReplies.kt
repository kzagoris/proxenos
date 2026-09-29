package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.coreapi.ActivityOutcome
import io.github.kzagoris.proxenos.coreapi.Collected
import io.github.kzagoris.proxenos.coreapi.CommandReply
import io.github.kzagoris.proxenos.coreapi.CommandResult
import io.github.kzagoris.proxenos.coreapi.Handle
import io.github.kzagoris.proxenos.coreapi.RunningCommand
import io.github.kzagoris.proxenos.coreapi.DirectoryListing
import io.github.kzagoris.proxenos.coreapi.Enumeration
import io.github.kzagoris.proxenos.coreapi.EntryKind
import io.github.kzagoris.proxenos.coreapi.FileContent
import io.github.kzagoris.proxenos.coreapi.FileWritten
import io.github.kzagoris.proxenos.coreapi.GitDiffReport
import io.github.kzagoris.proxenos.coreapi.GitLogReport
import io.github.kzagoris.proxenos.coreapi.GitScope
import io.github.kzagoris.proxenos.coreapi.GitStatusReport
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.Outcome
import io.github.kzagoris.proxenos.coreapi.SearchHit
import io.github.kzagoris.proxenos.coreapi.SearchResults
import io.github.kzagoris.proxenos.coreapi.WorkspaceListing
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * The MCP envelope, and nothing else (SPEC §5). The core has already decided the Outcome and
 * written the sentence the model reads; this file puts it in a `CallToolResult` and keeps the
 * text it was given **verbatim**. In particular an [Outcome.Uncertain] message carries
 * *effects uncertain, do not retry* (ADR 0001), and paraphrasing it here would reintroduce
 * the exact retry hazard that wording exists to prevent.
 *
 * `failed` and `uncertain` both travel as `isError`. ADR 0001 already expects a client to
 * flatten `uncertain` into a plain error, which is why the distinction lives in words a model
 * acts on rather than in a status field; claiming a non-error for an Operation whose effects
 * are unknown would be the worse of the two lies.
 */
internal fun reply(operation: Operation<*>, outcome: Outcome<*>): CallToolResult = when (outcome) {
  is Outcome.Ok -> text(render(operation, outcome.value).withRecordedNote(outcome), isError = outcome.carriesAnError())
  is Outcome.Failed -> text(outcome.message.withRecordedNote(outcome), isError = true)
  is Outcome.Uncertain -> text(outcome.message.withRecordedNote(outcome), isError = true)
}

/**
 * A repeat Delivery's answer is the first reply **verbatim**, and then the sentence saying it is
 * the recorded result of an Operation already performed (§6.4). Appended rather than woven in,
 * so the first reply reads exactly as it did the first time.
 */
private fun String.withRecordedNote(outcome: Outcome<*>): String =
  if (outcome.recorded) "$this\n\n${Outcome.RECORDED_RESULT}" else this

/**
 * A `get_result` that collected a `failed` or an `uncertain` travels as `isError` exactly as
 * the call that produced it would have. Collecting succeeded, so the Outcome is an `ok` — but
 * what the model has to act on is the outcome it collected, and claiming a non-error for an
 * Operation whose effects are unknown is the worse of the two lies (ADR 0001).
 */
private fun Outcome.Ok<*>.carriesAnError(): Boolean = (value as? Collected)?.carriesAnError() ?: false

private fun Collected.carriesAnError(): Boolean = when (this) {
  // Still running is not an error; it is not an answer either, and the words say so.
  is Collected.StillRunning -> false
  is Collected.Reached -> outcome !is Outcome.Ok
  is Collected.Recorded -> entry.outcome.unresolved
}

internal fun text(body: String, isError: Boolean): CallToolResult =
  CallToolResult(content = listOf(TextContent(body)), isError = isError)

/**
 * What an `ok` reads as. Keyed on the Operation rather than on the value's type so that it is
 * exhaustive: a new Operation whose result nobody has said how to render would otherwise fall
 * back on `toString`, and a data class printed at a model is not an answer.
 *
 * Every bound that bit is said out loud, because truncation is never silent (§6.5) and a
 * result that quietly stopped short reads as a complete one.
 */
private fun render(operation: Operation<*>, value: Any?): String = when (operation) {
  Operation.ListWorkspaces -> renderWorkspaces(value.asList())
  is Operation.ReadFile -> renderFile(value as FileContent)
  is Operation.ListDirectory -> renderListing(value as DirectoryListing)
  is Operation.Search -> renderSearch(value as SearchResults)
  is Operation.GitStatus -> renderStatus(value as GitStatusReport)
  is Operation.GitDiff -> renderDiff(value as GitDiffReport)
  is Operation.GitLog -> renderLog(value as GitLogReport)
  is Operation.WriteFile -> (value as FileWritten).let {
    "${it.path}: ${if (it.created) "created" else "replaced"}, ${it.bytes} bytes."
  }
  is Operation.EditFile -> (value as FileWritten).let { "${it.path}: edited, ${it.bytes} bytes." }
  is Operation.RunCommand -> when (val reply = value as CommandReply) {
    is CommandReply.Finished -> renderCommand(reply.result)
    is CommandReply.Promoted -> renderPromoted(operation.workspace, reply.handle, reply.running)
  }
  is Operation.GetResult -> renderCollected(operation, value as Collected)
}

@Suppress("UNCHECKED_CAST") // ListWorkspaces fixes its own result type.
private fun Any?.asList(): List<WorkspaceListing> = this as List<WorkspaceListing>

/**
 * A Workspace at None is already absent from this list and a Broken one with it (§2.3), so
 * what arrives here is the whole of what the connector exposes and is presented as such.
 */
private fun renderWorkspaces(workspaces: List<WorkspaceListing>): String =
  if (workspaces.isEmpty()) "No Workspaces are exposed."
  else buildString {
    appendLine("${workspaces.size} ${plural(workspaces.size, "Workspace", "Workspaces")}:")
    workspaces.forEach {
      appendLine(
        "  ${it.name}  ${it.root}  ${it.accessLevel}  " +
          if (it.isGitRepository) "Git repository" else "not a Git repository",
      )
    }
  }.trimEnd()

/**
 * The header states the file's total line count before a byte of it, because that is what the
 * model pages by: an answer that did not say how much was left out reads as the whole file.
 */
private fun renderFile(file: FileContent): String = buildString {
  val last = file.firstLine + file.lineCount - 1
  append(
    if (file.lineCount == 0) "${file.path}: no lines at line ${file.firstLine} of ${file.totalLines}."
    else "${file.path}: lines ${file.firstLine}-$last of ${file.totalLines}.",
  )
  if (file.cappedByBytes) {
    append(" The byte cap ended this read before the line limit did; continue with offset ${last + 1}.")
  }
  if (file.lineCount > 0) body(file.text)
}

private fun renderListing(listing: DirectoryListing): String = buildString {
  append("${listing.path}: ")
  // There is no offset to page a listing by, so what the cap dropped is dropped for good.
  tally(
    listing.entries.size, listing.totalEntries, "entry", "entries",
    listing.dropped("of names dropped from the middle"),
  )
  each(listing.entries) {
    "${it.name}${if (it.kind == EntryKind.Directory) "/" else ""}  ${it.kind.said}" +
      (it.sizeBytes?.let { size -> ", $size bytes" } ?: "")
  }
}

/** A symlink is reported as one rather than as what it points at, which is not followed (§4). */
private val EntryKind.said: String
  get() = when (this) {
    EntryKind.File -> "file"
    EntryKind.Directory -> "directory"
    EntryKind.Symlink -> "symlink, not followed"
    EntryKind.Other -> "neither file nor directory"
  }

private fun renderSearch(results: SearchResults): String = buildString {
  append("${results.hits.size} ${plural(results.hits.size, "hit", "hits")} in ")
  append("${results.filesSearched} ${plural(results.filesSearched, "file", "files")} under ")
  append("'${results.path}', enumerated by ${results.enumeration.said}.")
  // Each bound names itself: "12 hits" that was really "12 hits and then time ran out" reads
  // as a finished search of the whole project, which is a different fact about this machine.
  with(results.bounds) {
    if (cappedByResults) append(" Capped at ${Operation.SEARCH_RESULT_CAP} results, so there may be more.")
    if (cappedByTime) append(" Truncated by time, so there may be more.")
    if (droppedHits > 0) append(" $droppedHits hits ($droppedBytes bytes) were dropped from the middle.")
    if (linesNotFullySearched > 0) {
      append(
        " $linesNotFullySearched lines were too long to search whole, so a miss in those files " +
          "is not proof the query is absent.",
      )
    }
  }
  each(results.hits) {
    when (it) {
      is SearchHit.Name -> "${it.path}  (the name matched)"
      is SearchHit.Content -> "${it.path}:${it.line}: ${it.text}" + if (it.lineCut) "  (line cut)" else ""
    }
  }
}

private val Enumeration.said: String
  get() = when (this) {
    Enumeration.GitIgnoreRules -> "the project's own Git ignore rules"
    Enumeration.PlainWalk -> "a plain walk, since the subtree is in no Git repository"
  }

/**
 * Scope leads every Git answer. An empty `git_status` that did not say it had been Scoped
 * reads as "the repository is clean", which is a different, and wrong, fact (§4).
 */
private fun GitScope.said(): String = scopedTo?.let {
  "Repository ${repository}, scoped to '$it': paths elsewhere in the repository were not looked at."
} ?: "Repository $repository, which the Root is the root of."

private fun renderStatus(report: GitStatusReport): String = buildString {
  appendLine(report.scope.said())
  tally(report.entries.size, report.totalEntries, "change", "changes", report.dropped("dropped from the middle"))
  each(report.entries) {
    "${it.index}${it.workTree} ${it.originalPath?.let { from -> "$from -> " } ?: ""}${it.path}"
  }
}

private fun renderDiff(report: GitDiffReport): String = buildString {
  appendLine(report.scope.said())
  append(if (report.staged) "Staged changes only." else "Working tree against HEAD, staged and unstaged together.")
  if (report.cappedByBytes) append(" ${report.droppedBytes} bytes were cut from the middle of the patch.")
  if (report.patch.isEmpty()) append(" No changes.") else body(report.patch)
}

private fun renderLog(report: GitLogReport): String = buildString {
  appendLine(report.scope.said())
  tally(report.commits.size, report.totalCommits, "commit", "commits", report.dropped("dropped from the old end"))
  each(report.commits) { "${it.hash}  ${it.date}  ${it.author}  ${it.subject}" }
}

/**
 * **Promoted is not worded as success** (§6.2, ADR 0003). It is a reply that precedes its
 * Operation's outcome, so the first thing it says is that no outcome exists yet — a sentence
 * that read "started successfully" would be a model's cue to move on from a build it has not
 * seen the end of.
 */
private fun renderPromoted(workspace: String?, handle: Handle, running: RunningCommand): String = buildString {
  append("'${running.command}' in '${running.cwd}' has been running for ")
  append("${running.elapsed.inWholeSeconds}s and has not finished. This is not a result: the ")
  append("command is still running on this machine and its outcome does not exist yet. ")
  append("Collect it with get_result, handle '${handle.value}'")
  workspace?.let { append(" and workspace '$it'") }
  append("; call get_result again if it is still running then.")
  if (running.droppedBytes > 0) {
    append(" ${running.droppedBytes} bytes have been dropped from the middle of its output so far.")
  }
  if (running.outputSoFar.isEmpty()) append(" It has printed nothing so far.")
  else {
    append(" What it has printed so far:")
    body(running.outputSoFar)
  }
}

/**
 * What a Handle resolved to (§4). The three shapes are the three true things there are to say:
 * it is still running, it reached an outcome, or this Runtime is not the one that ran it and
 * the account is all there is.
 */
private fun renderCollected(operation: Operation.GetResult, collected: Collected): String = when (collected) {
  is Collected.StillRunning -> renderPromoted(operation.workspace, collected.handle, collected.running)
  is Collected.Reached -> when (val outcome = collected.outcome) {
    // The command's own reply, unchanged: collecting it late does not make it a different fact.
    is Outcome.Ok -> renderCommand(outcome.value)
    is Outcome.Failed -> outcome.message
    // Verbatim, including *effects uncertain, do not retry* — paraphrasing it here is the
    // retry hazard ADR 0001 exists to prevent, and lateness does not soften it.
    is Outcome.Uncertain -> outcome.message
  }
  is Collected.Recorded -> renderRecorded(collected)
}

/**
 * A Handle this Runtime holds no result for. A Handle whose Runtime has Stopped resolves to the
 * **Lost** Operation it points at (§6.2), and that is said plainly rather than dressed as a
 * result: Lost produced nothing to trust or distrust.
 */
private fun renderRecorded(collected: Collected.Recorded): String = buildString {
  val entry = collected.entry
  append("'${entry.arguments}' is in this machine's account, and this Runtime is not holding ")
  append("its output: ")
  if (entry.outcome == ActivityOutcome.Lost) {
    // Said rather than dressed as a result: Lost produced nothing to trust or distrust.
    append(
      "it was Lost. The Runtime running it was taken from the machine before it finished, so " +
        "no result exists at all — ${Outcome.Uncertain.EFFECTS_UNCERTAIN}.",
    )
    return@buildString
  }
  append("the account reads ${entry.outcome.said}.")
  // Whatever the account recorded beside it, unedited. The core wrote that sentence when the
  // Operation ended, and rewriting it here would be a second account of one thing.
  entry.outcome.detail?.let { body(it) }
}

/**
 * A non-zero exit is an `ok` (§5): the Operation ran the command it was asked to, and what the
 * command thought of itself is its exit code's to say. Standard output and standard error
 * arrive interleaved, in the order the command wrote them.
 */
private fun renderCommand(result: CommandResult): String = buildString {
  append("'${result.command}' in '${result.cwd}' exited ${result.exitCode}")
  if (result.cappedByBytes) append(", ${result.droppedBytes} bytes dropped from the middle of its output")
  append(".")
  if (result.output.isEmpty()) append(" It printed nothing.") else body(result.output)
}

/**
 * How many arrived against how many there are, and what a cap took. The two are one sentence
 * because they are one fact: a count that did not say what was left out reads as the whole of
 * it, and truncation is never silent (§6.5).
 */
private fun StringBuilder.tally(shown: Int, total: Int, one: String, many: String, dropped: String?) {
  append("$shown of $total ${plural(total, one, many)}")
  dropped?.let { append(", $it") }
  append(".")
}

/** The bytes a cap took, said the way this report says it; null when nothing was dropped. */
private fun GitStatusReport.dropped(how: String): String? = "$droppedBytes bytes $how".takeIf { cappedByBytes }
private fun GitLogReport.dropped(how: String): String? = "$droppedBytes bytes $how".takeIf { cappedByBytes }
private fun DirectoryListing.dropped(how: String): String? = "$droppedBytes bytes $how".takeIf { cappedByBytes }

/** One indented line per item, under the sentence that said how many there are. */
private fun <T> StringBuilder.each(items: List<T>, line: (T) -> String) = items.forEach {
  appendLine()
  append("  ${line(it)}")
}

/** A blank line, then the thing itself. What was left out was said in the header above it. */
private fun StringBuilder.body(text: String) {
  appendLine()
  appendLine()
  append(text)
}

private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many
