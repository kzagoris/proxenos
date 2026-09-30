package io.github.kzagoris.proxenos.coreapi

import kotlinx.serialization.Serializable

/**
 * Where a Git tool ran, and how much of the repository it was allowed to see. Part of every
 * answer rather than a detail: where the Root sits *inside* a larger repository the tool runs
 * at the repository root but is pathspec-scoped to the Root, and an empty `git_status` that did
 * not say so would be read as "the repository is clean".
 */
@Serializable
data class GitScope(
  /** Absolute path of the repository the invocation ran at. */
  val repository: String,
  /**
   * The Root's path relative to [repository], and null when the Root *is* the repository root.
   * Non-null is the whole of what "scoped" means here: paths outside it were not looked at.
   */
  val scopedTo: String?,
) {
  val scoped: Boolean get() = scopedTo != null
}

/**
 * One porcelain line of `git status`. [index] and [workTree] are Git's own two status
 * characters, a space where that side is unchanged and `?` on both for an untracked file, kept
 * as Git prints them rather than translated into a vocabulary of our own.
 */
@Serializable
data class GitStatusEntry(
  /** Relative to the **Root**, so it is a path the file tools can be called with. */
  val path: String,
  val index: Char,
  val workTree: Char,
  /** Where a rename or copy came from, Root-relative like [path]; null for everything else. */
  val originalPath: String?,
)

/**
 * What `git status` found. [totalEntries] against `entries.size` is how the caller knows what
 * the byte cap left out — truncation is never silent.
 */
@Serializable
data class GitStatusReport(
  val scope: GitScope,
  val entries: List<GitStatusEntry>,
  val totalEntries: Int,
  /** Bytes of entries the cap dropped from the middle; 0 when the report is whole. */
  val droppedBytes: Int,
) {
  val cappedByBytes: Boolean get() = droppedBytes > 0
}

/**
 * The patch `git diff` produced, as Git wrote it. The paths inside it are the repository's own,
 * which is what makes it a patch somebody could apply; [GitScope.scopedTo] says which subtree
 * of the repository it could have covered.
 */
@Serializable
data class GitDiffReport(
  val scope: GitScope,
  /** The staged changes alone, rather than the working tree against `HEAD`. */
  val staged: Boolean,
  val patch: String,
  /** Bytes cut from the middle by the 32 KiB head-and-tail bound; 0 when none were. */
  val droppedBytes: Int,
) {
  val cappedByBytes: Boolean get() = droppedBytes > 0
}

/** One commit, newest first in a [GitLogReport]. */
@Serializable
data class GitCommit(
  /** The full hash. Abbreviating is the reader's business, and an abbreviation can collide. */
  val hash: String,
  val subject: String,
  val author: String,
  /** The author date, strict ISO-8601 in the author's own offset, exactly as Git printed it. */
  val date: String,
)

/**
 * The commits `git log` returned, newest first. [totalCommits] against `commits.size` is what
 * the byte cap left out; the oldest go first, because a log is read from the recent end.
 */
@Serializable
data class GitLogReport(
  val scope: GitScope,
  val commits: List<GitCommit>,
  val totalCommits: Int,
  /** Bytes of commits the cap dropped from the old end; 0 when the log is whole. */
  val droppedBytes: Int,
) {
  val cappedByBytes: Boolean get() = droppedBytes > 0
}
