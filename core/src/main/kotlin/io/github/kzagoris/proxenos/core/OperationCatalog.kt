package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*

/**
 * The eleven, as data (SPEC §3, §4). All eleven ship at once because ChatGPT snapshots the
 * catalog with no refresh: adding a tool later is not a version bump, it is every user deleting
 * and re-creating their connector by hand. The entries whose Operation has not landed yet are
 * still catalog, and [specFor] is where an Operation is bound to one.
 */
internal object OperationCatalog {
  private const val WORKSPACE = "The Workspace this call names. Required; there is no default."
  // SPEC §6.4 fixes this wording: the tool description is the only lever on a repeat the model
  // initiates itself, so it is phrased as an instruction rather than as documentation.
  private const val REQUEST_ID =
    "Supply a fresh unique request_id for each operation you intend to perform. If a call " +
      "returns uncertain or times out, repeat it with the same request_id - this returns the " +
      "original result instead of performing the operation twice. Use a new id only when you " +
      "intend a separate execution."

  private fun workspace() = ArgumentSpec(Operation.WORKSPACE_ARGUMENT, ArgumentType.Text, true, WORKSPACE)
  private fun requestId() = ArgumentSpec(Operation.KEY_ARGUMENT, ArgumentType.Text, true, REQUEST_ID)

  val ENTRIES: List<OperationSpec> = listOf(
    OperationSpec(
      "list_workspaces",
      "List the Workspaces this connector exposes, with Root, Access Level and whether the Root is a Git repository.",
      null, // Unscoped: it names no Workspace, so no Access Level governs it.
      emptyList(),
    ),
    OperationSpec(
      "list_directory", "List the entries of a directory inside the Workspace's Root.",
      AccessLevel.Read,
      listOf(
        workspace(),
        ArgumentSpec("path", ArgumentType.Text, false, "Directory relative to the Root; the Root itself by default."),
      ),
    ),
    OperationSpec(
      "read_file",
      "Read a text file, line-oriented and bounded. The reply states the file's total line count, " +
        "so page with offset rather than guessing what was left out.",
      AccessLevel.Read,
      listOf(
        workspace(),
        ArgumentSpec("path", ArgumentType.Text, true, "File relative to the Root."),
        ArgumentSpec("offset", ArgumentType.Integer, false, "1-based line to start at; the first line by default."),
        ArgumentSpec(
          "limit", ArgumentType.Integer, false,
          "Lines to return, at most ${Operation.DEFAULT_LINE_CEILING} by default. " +
            "A ${Operation.OUTPUT_CAP_BYTES / 1024} KiB cap may bind first.",
        ),
      ),
    ),
    OperationSpec(
      "search", "Search the Workspace for a filename or file contents.",
      AccessLevel.Read,
      listOf(
        workspace(),
        ArgumentSpec("query", ArgumentType.Text, true, "Literal substring unless regex is set."),
        ArgumentSpec("path", ArgumentType.Text, false, "Subtree relative to the Root; the whole Root by default."),
        ArgumentSpec("regex", ArgumentType.Flag, false, "Read the query as a regular expression."),
        ArgumentSpec("case_sensitive", ArgumentType.Flag, false, "Match case."),
      ),
    ),
    OperationSpec(
      "git_status",
      "Git status of the Root. Where the Root sits inside a larger repository the answer covers " +
        "the Root alone and says so, so no changes here does not mean the repository is clean.",
      AccessLevel.Read, listOf(workspace()),
    ),
    OperationSpec(
      "git_diff",
      "Working-tree diff against HEAD, staged and unstaged together by default. Scoped to the " +
        "Root, and the reply says so, when the Root sits inside a larger repository.",
      AccessLevel.Read,
      listOf(workspace(), ArgumentSpec("staged", ArgumentType.Flag, false, "Show staged changes only.")),
    ),
    OperationSpec(
      "git_log",
      "Recent commits with hash, subject, author and date. Scoped to the Root, and the reply " +
        "says so, when the Root sits inside a larger repository.",
      AccessLevel.Read,
      listOf(workspace(), ArgumentSpec(
          "limit", ArgumentType.Integer, false,
          "Commits to return; ${Operation.GIT_LOG_COMMITS} by default.",
        )),
    ),
    OperationSpec(
      "write_file",
      "Write a file whole. Missing parent directories are not created, and the file is replaced " +
        "rather than rewritten in place, so hardlinks to it break.",
      AccessLevel.Write,
      listOf(
        workspace(),
        ArgumentSpec("path", ArgumentType.Text, true, "File relative to the Root."),
        ArgumentSpec("content", ArgumentType.Text, true, "The file's new contents."),
        requestId(),
      ),
    ),
    OperationSpec(
      "edit_file",
      "Replace an exact string that must occur exactly once in the file. Zero matches and " +
        "several are both refused and change nothing; the file is replaced rather than " +
        "rewritten in place, so hardlinks to it break.",
      AccessLevel.Write,
      listOf(
        workspace(),
        ArgumentSpec("path", ArgumentType.Text, true, "File relative to the Root."),
        ArgumentSpec("old_text", ArgumentType.Text, true, "Byte-exact text that must occur exactly once."),
        ArgumentSpec("new_text", ArgumentType.Text, true, "What replaces it."),
        requestId(),
      ),
    ),
    OperationSpec(
      "run_command",
      "Run a command with the full authority of this Linux account, which is wider than the Root.",
      AccessLevel.Command,
      listOf(
        workspace(),
        ArgumentSpec("command", ArgumentType.Text, true, "A single command line, run through /bin/sh -c."),
        ArgumentSpec("cwd", ArgumentType.Text, false, "Working directory relative to the Root; the Root by default."),
        requestId(),
      ),
    ),
    OperationSpec(
      "get_result",
      "Collect the output of a command that outran the call which started it. It answers either " +
        "that the command is still running, with what it has printed so far, or with the " +
        "outcome it reached. Collecting twice collects the same thing.",
      AccessLevel.Command,
      listOf(workspace(), ArgumentSpec("handle", ArgumentType.Text, true, "The Handle that call answered with.")),
    ),
  )

  /**
   * The Operation's entry, and with it the Access Level admission checks. The `when` is
   * exhaustive, so a new Operation does not compile until it has a catalog entry — which is
   * how "the required level" stops being something eleven call sites each decide for themselves.
   */
  fun specFor(op: Operation<*>): OperationSpec = when (op) {
    Operation.ListWorkspaces -> entry("list_workspaces")
    is Operation.ReadFile -> entry("read_file")
    is Operation.ListDirectory -> entry("list_directory")
    is Operation.Search -> entry("search")
    is Operation.GitStatus -> entry("git_status")
    is Operation.GitDiff -> entry("git_diff")
    is Operation.GitLog -> entry("git_log")
    is Operation.WriteFile -> entry("write_file")
    is Operation.EditFile -> entry("edit_file")
    is Operation.RunCommand -> entry("run_command")
    is Operation.GetResult -> entry("get_result")
  }

  /**
   * The arguments as Activity summarises them (SPEC §10.1). Exhaustive for the same reason
   * [specFor] is: an Operation whose arguments nobody has said how to summarise would show up
   * in the account as a tool name and nothing else, which is a line the user cannot act on.
   */
  fun argumentsOf(op: Operation<*>): String = when (op) {
    Operation.ListWorkspaces -> ""
    is Operation.ReadFile -> listOfNotNull(
      "path=${op.path}",
      op.offset?.let { "offset=$it" },
      op.limit?.let { "limit=$it" },
    ).joinToString(" ")
    is Operation.ListDirectory -> "path=${op.label}"
    is Operation.Search -> listOfNotNull(
      "query=${op.query}",
      op.path?.let { "path=$it" },
      "regex".takeIf { op.regex },
      "case_sensitive".takeIf { op.caseSensitive },
    ).joinToString(" ")
    is Operation.GitStatus -> ""
    is Operation.GitDiff -> if (op.staged) "staged" else ""
    is Operation.GitLog -> op.limit?.let { "limit=$it" } ?: ""
    // The Delivery key is in the account because it is what tells one arrival of a mutation
    // from a repeat of it, and a user reading an unattended afternoon has no other way to.
    is Operation.WriteFile -> "path=${op.path} content_bytes=${op.content.utf8Size()} request_id=${op.deliveryKey}"
    is Operation.EditFile -> "path=${op.path} request_id=${op.deliveryKey}"
    // The command itself, whole: this line is the whole of what an unattended Command Workspace
    // can be reviewed by, and a command summarised into its first few words is not reviewable.
    is Operation.RunCommand -> "command=${op.command} cwd=${op.label} request_id=${op.deliveryKey}"
    is Operation.GetResult -> "handle=${op.handle}"
  }

  /**
   * What an `ok` Outcome put in the account. Keyed on the Operation rather than on its result
   * so that it is exhaustive like [specFor]: a new Operation whose result nobody has said how
   * to render would otherwise fall back on `toString`, and an account that reads like a
   * debugger is not one anybody reviews an unattended afternoon with.
   *
   * A command's output arrives here whole (§6.5) — there is no overflow-to-file. A read's does
   * not: the account is of what ran, and the file it read is still where it was.
   */
  fun outputOf(op: Operation<*>, value: Any?): String = when (op) {
    Operation.ListWorkspaces -> "${(value as List<*>).size} Workspaces"
    is Operation.ReadFile -> (value as FileContent).let {
      "lines ${it.firstLine}-${it.firstLine + it.lineCount - 1} of ${it.totalLines}" +
        if (it.cappedByBytes) ", capped at the byte limit" else ""
    }
    is Operation.ListDirectory -> (value as DirectoryListing).let {
      "${it.entries.size} of ${it.totalEntries} entries" +
        if (it.cappedByBytes) ", ${it.droppedBytes} bytes dropped" else ""
    }
    // Every bound that bit is named here too: the account is what a user reviews an unattended
    // afternoon with, and "12 hits" that was really "12 hits and then time ran out" reads as a
    // finished search of the whole project.
    is Operation.Search -> (value as SearchResults).let {
      "${it.hits.size} ${plural(it.hits.size, "hit", "hits")} in " +
        "${it.filesSearched} ${plural(it.filesSearched, "file", "files")}" +
        (if (it.bounds.cappedByResults) ", capped at ${Operation.SEARCH_RESULT_CAP} results" else "") +
        (if (it.bounds.cappedByTime) ", truncated by time" else "") +
        (if (it.bounds.droppedHits > 0) ", ${it.bounds.droppedBytes} bytes dropped" else "") +
        (if (it.bounds.linesNotFullySearched > 0) ", ${it.bounds.linesNotFullySearched} long lines cut" else "")
    }
    // The scope is in the account for the reason it is in the reply: "0 changes" against a
    // Root inside a larger repository is not a clean repository, and a user reading back an
    // unattended afternoon has no other way to tell the two apart.
    is Operation.GitStatus -> (value as GitStatusReport).let {
      "${it.totalEntries} ${plural(it.totalEntries, "change", "changes")}${scoped(it.scope)}" +
        if (it.cappedByBytes) ", ${it.droppedBytes} bytes dropped" else ""
    }
    is Operation.GitDiff -> (value as GitDiffReport).let {
      "${it.patch.utf8Size()} bytes of patch${scoped(it.scope)}" +
        if (it.cappedByBytes) ", ${it.droppedBytes} bytes dropped" else ""
    }
    is Operation.GitLog -> (value as GitLogReport).let {
      "${it.commits.size} of ${it.totalCommits} ${plural(it.totalCommits, "commit", "commits")}${scoped(it.scope)}"
    }
    is Operation.WriteFile -> (value as FileWritten).let {
      "${if (it.created) "created" else "replaced"}, ${it.bytes} bytes"
    }
    is Operation.EditFile -> "edited, ${(value as FileWritten).bytes} bytes"
    // A command's output arrives whole (§6.5) — capped where it was produced, and with no
    // overflow-to-file, Activity is where the whole of an unattended afternoon lives.
    //
    // A Promoted reply never reaches here: it precedes its outcome, so the entry it belongs to
    // is still open and is closed later by the Runtime-scoped coroutine carrying it (§6.2). The branch is
    // written out all the same, because an exhaustive `when` is what keeps this honest.
    is Operation.RunCommand -> when (val reply = value as CommandReply) {
      is CommandReply.Finished -> reply.result.let {
        "exit ${it.exitCode}" + (if (it.cappedByBytes) ", ${it.droppedBytes} bytes dropped" else "") +
          (if (it.output.isEmpty()) ", no output" else "\n${it.output}")
      }
      is CommandReply.Promoted -> "promoted, still running as handle ${reply.handle.value}"
    }
    // What the collection did, never a second copy of what it collected: the outcome belongs to
    // the entry the handle names, and that entry already carries it.
    is Operation.GetResult -> when (val collected = value as Collected) {
      is Collected.StillRunning -> "handle ${op.handle}: still running"
      is Collected.Reached -> "handle ${op.handle}: collected"
      is Collected.Recorded -> "handle ${op.handle}: read back from the account as " +
        collected.entry.outcome.said
    }
  }

  /**
   * What the account records of an Outcome. Ok carries the Operation's own output, which lives
   * here whole: an Operation's output is capped where it is produced and there is no
   * overflow-to-file, since a spill file outside every Root is a path the model is told about
   * and cannot read.
   *
   * It lives beside [outputOf] rather than in the pipeline because a promoted command's entry
   * is completed by the Runtime-scoped coroutine that carried it, long after the pipeline's own call
   * has returned — and one account of an Operation is the whole point.
   */
  fun recordOf(op: Operation<*>, outcome: Outcome<*>): ActivityOutcome = when (outcome) {
    is Outcome.Ok -> ActivityOutcome.Ok(outputOf(op, outcome.value))
    is Outcome.Failed -> ActivityOutcome.Failed(outcome.message)
    is Outcome.Uncertain -> ActivityOutcome.Uncertain(outcome.message)
  }

  private fun scoped(scope: GitScope): String = scope.scopedTo?.let { ", scoped to '$it'" } ?: ""

  private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

  private fun entry(name: String): OperationSpec = ENTRIES.single { it.name == name }
}
