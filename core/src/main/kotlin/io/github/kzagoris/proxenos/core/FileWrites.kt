package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.text.Charsets.UTF_8

/**
 * The two mutating file tools. Both land the same way — a temp file beside the target
 * and an atomic rename — so that a reader of the file sees the whole of one version or the whole
 * of the other, never a half-written file. Both are called holding the mutation lock the
 * pipeline took on the target's resolved real path.
 *
 * What lands is the file's own convention, not the caller's: permission bits, line endings and
 * the presence or absence of a trailing newline all survive a write, because a tool that
 * silently converted a CRLF file would turn one changed line into a diff of the whole file.
 *
 * The consequence of landing by rename is on the record rather than left to be discovered:
 * **rename replaces the inode, so hardlinks to the file break** — a hardlink is left holding
 * what the file used to be.
 */
internal fun writeFile(op: Operation.WriteFile, workspace: Workspace, file: Path): Outcome<FileWritten> {
  val target = Target(workspace, op.path, file)
  // Not created, only found: a hallucinated path fails loudly rather than growing a tree.
  val parent = file.parent
  if (parent == null || !Files.isDirectory(parent)) return Outcome.Failed(
    Failure.NotADirectory(workspace.name, target.parentLabel),
    "This Operation failed and changed nothing: '${target.parentLabel}' is not a directory in " +
      "Workspace '${workspace.name}'. Missing parent directories are not created, so create it " +
      "first if that is what you meant.",
  )
  val existing = target.readExisting().valueOr { problem -> return problem }
  if (existing == null && Files.exists(file)) return target.notAFile()

  val convention = Convention.of(existing)
  val text = convention.withTrailingNewlineOf(existing, convention.render(op.content))
  return target.land(text.toByteArray(UTF_8), created = existing == null)
}

/** Every match is checked against one original version before one replacement is staged. */
internal fun editFile(op: Operation.EditFile, workspace: Workspace, file: Path): Outcome<FileWritten> {
  val target = Target(workspace, op.path, file)
  val single = op.oldText != null && op.newText != null
  if ((op.edits != null && (op.oldText != null || op.newText != null)) ||
    (op.edits == null && !single)
  ) return Outcome.Failed(
    Failure.InvalidArgument("edits"),
    "This Operation failed and changed nothing: supply exactly one of edits or the old_text/new_text pair.",
  )
  val edits = op.edits ?: listOf(TextEdit(op.oldText!!, op.newText!!))
  if (edits.size !in 1..100) return Outcome.Failed(
    Failure.InvalidArgument("edits"),
    "This Operation failed and changed nothing: edits must contain 1–100 replacements.",
  )
  val empty = edits.indexOfFirst { it.oldText.isEmpty() }
  if (empty >= 0) return Outcome.Failed(
    Failure.InvalidArgument(if (op.edits == null) "old_text" else "edits[$empty].old_text"),
    "This Operation failed and changed nothing: old_text in edit $empty is empty. Quote the text to replace.",
    conflicts = listOf(EditConflict(empty, 0)),
  )
  val existing = target.readExisting().valueOr { problem -> return problem } ?: return target.notAFile()
  if (existing.contains(ZERO)) return Outcome.Failed(
    Failure.Binary(workspace.name, op.path),
    "This Operation failed and changed nothing: '${op.path}' is a binary file, so there is no text to edit.",
  )

  val convention = Convention.of(existing)
  val matches = edits.map { occurrencesOf(existing, it.oldText.toByteArray(UTF_8)) }
  val conflicts = matches.mapIndexedNotNull { index, places ->
    if (places.size == 1) null else EditConflict(index, places.size)
  }
  if (conflicts.isNotEmpty()) {
    val first = conflicts.first()
    return Outcome.Failed(
      if (first.matches == 0) Failure.NoMatch(workspace.name, op.path)
      else Failure.SeveralMatches(workspace.name, op.path, first.matches),
      "This Operation failed and changed nothing: " + conflicts.joinToString("; ") {
        "edit ${it.index} old_text occurs ${it.matches} times in '${op.path}' (exactly one is required)"
      } + ". Match byte for byte, line endings included; read the file again and quote enough surrounding text.",
      conflicts = conflicts,
    )
  }
  val replacements = edits.mapIndexed { index, edit ->
    Replacement(index, matches[index].single(), edit.oldText.toByteArray(UTF_8).size,
      convention.render(edit.newText).toByteArray(UTF_8))
  }.sortedBy { it.at }
  val overlaps = mutableListOf<EditConflict>()
  for (index in replacements.indices) {
    val current = replacements[index]
    for (previousIndex in 0 until index) {
      val previous = replacements[previousIndex]
      if (current.at < previous.end) {
        overlaps += EditConflict(current.index, 1, previous.index)
        break
      }
    }
  }
  if (overlaps.isNotEmpty()) return Outcome.Failed(
    Failure.InvalidArgument("edits"),
    "This Operation failed and changed nothing: " + overlaps.joinToString("; ") {
      "edit ${it.index} overlaps edit ${it.overlapsWith}"
    } + ". Quote non-overlapping regions of the original file.",
    conflicts = overlaps,
  )

  val bytes = replaceRegions(existing, 0, existing.size, replacements)
  val diff = editDiff(op.path, existing, replacements)
  if (op.preview) return Outcome.Ok(FileWritten(op.path, bytes.size, created = false,
    preview = true, diff = diff.text.toString(), diffTruncated = diff.truncated))
  return when (val landed = target.land(bytes, created = false)) {
    is Outcome.Ok -> Outcome.Ok(landed.value.copy(diff = diff.text.toString(), diffTruncated = diff.truncated))
    is Outcome.Failed -> landed
    is Outcome.Uncertain -> landed
  }
}

private data class Replacement(val index: Int, val at: Int, val size: Int, val bytes: ByteArray) {
  val end: Int get() = at + size
}

private fun replaceRegions(original: ByteArray, start: Int, end: Int, replacements: List<Replacement>): ByteArray {
  val output = ByteArrayOutputStream()
  var cursor = start
  for (replacement in replacements) {
    output.write(original, cursor, replacement.at - cursor)
    output.write(replacement.bytes)
    cursor = replacement.end
  }
  output.write(original, cursor, end - cursor)
  return output.toByteArray()
}

/** Whole-line hunks include the following line so removing a newline still describes a full line. */
private fun editDiff(path: String, original: ByteArray, replacements: List<Replacement>): BoundedDiff {
  val diff = BoundedDiff()
  diff.append("--- a/$path\n+++ b/$path\n")
  var next = 0
  var lineDelta = 0
  while (next < replacements.size && !diff.truncated) {
    val first = replacements[next]
    var start = first.at
    while (start > 0 && original[start - 1] != NEWLINE) start--
    fun lineEnd(at: Int): Int {
      var end = at
      while (end < original.size && original[end - 1] != NEWLINE) end++
      if (end < original.size) {
        end++
        while (end < original.size && original[end - 1] != NEWLINE) end++
      }
      return end
    }
    var end = lineEnd(first.end)
    val group = mutableListOf(first)
    next++
    while (next < replacements.size && replacements[next].at <= end) {
      val following = replacements[next]
      // At a line boundary this is the next line; including it gives one coherent hunk.
      group += following
      end = lineEnd(following.end)
      next++
    }
    val before = original.copyOfRange(start, end)
    val after = replaceRegions(original, start, end, group)
    val oldLines = lineCount(before)
    val newLines = lineCount(after)
    var oldStart = 1
    for (index in 0 until start) if (original[index] == NEWLINE) oldStart++
    val newStart = oldStart + lineDelta
    diff.append("@@ -${if (oldLines == 0) oldStart - 1 else oldStart},$oldLines +${if (newLines == 0) newStart - 1 else newStart},$newLines @@\n")
    diff.lines('-', before)
    diff.lines('+', after)
    lineDelta += newLines - oldLines
  }
  return diff
}

private fun lineCount(bytes: ByteArray): Int =
  bytes.count { it == NEWLINE } + if (bytes.isNotEmpty() && bytes.last() != NEWLINE) 1 else 0

private class BoundedDiff {
  val text = StringBuilder()
  var truncated = false
    private set
  private var bytes = 0

  fun append(value: String) {
    if (truncated) return
    var index = 0
    while (index < value.length) {
      val point = value.codePointAt(index)
      val size = when {
        point <= 0x7f -> 1
        point <= 0x7ff -> 2
        point <= 0xffff -> 3
        else -> 4
      }
      if (bytes + size > 8 * 1024) {
        truncated = true
        return
      }
      text.appendCodePoint(point)
      bytes += size
      index += Character.charCount(point)
    }
  }

  fun lines(prefix: Char, content: ByteArray) {
    if (content.isEmpty() || truncated) return
    val value = String(content, UTF_8)
    var start = 0
    while (start < value.length && !truncated) {
      val newline = value.indexOf('\n', start)
      val end = if (newline < 0) value.length else newline + 1
      append(prefix.toString())
      append(value.substring(start, end))
      if (newline < 0) append("\n\\ No newline at end of file\n")
      start = end
    }
  }
}

/**
 * The one file a mutation is about: the Workspace it was named in, the name the call gave it,
 * and what confinement resolved that to. The three travel together through every step below,
 * and every failure needs all three — the reason names the Workspace, the message quotes the
 * path the caller can act on, and only the resolved one may be touched.
 */
private class Target(val workspace: Workspace, val path: String, val file: Path) {
  /** The parent as the caller named it, which is the path they can act on; the Root when there is none. */
  val parentLabel: String get() = Path.of(path).parent?.toString() ?: Operation.THIS_DIRECTORY

  /**
   * The bytes of a regular file, null where there is no regular file to read. A file that
   * cannot be read is a `failed` rather than an exception: nothing has been written at this
   * point, so the guarantee that nothing changed still holds and the caller may retry.
   */
  fun readExisting(): Outcome<ByteArray?> = try {
    if (Files.isRegularFile(file)) Outcome.Ok(Files.readAllBytes(file)) else Outcome.Ok(null)
  } catch (failure: IOException) {
    val detail = failure.message ?: failure.toString()
    Outcome.Failed(
      Failure.IoError(workspace.name, detail),
      "This Operation failed and changed nothing: '$path' could not be read: $detail",
    )
  }

  fun notAFile(): Outcome.Failed = Outcome.Failed(
    Failure.NotAFile(workspace.name, path),
    "This Operation failed and changed nothing: " +
      if (Files.exists(file)) "'$path' is not a regular file."
      else "'$path' does not exist in Workspace '${workspace.name}'.",
  )
}

/**
 * Every place [needle] sits in [haystack], overlaps included: two matches that share bytes are
 * still two places the caller could have meant — `aa` sits twice in `aaa` — and counting them
 * as one would be first-one-wins by arithmetic.
 */
private fun occurrencesOf(haystack: ByteArray, needle: ByteArray): List<Int> {
  val found = mutableListOf<Int>()
  var index = 0
  while (index + needle.size <= haystack.size) {
    var offset = 0
    while (offset < needle.size && haystack[index + offset] == needle[offset]) offset++
    if (offset == needle.size) found.add(index)
    index++
  }
  return found
}

/**
 * The line endings a file already uses. A file with no line ending at all — or one that is
 * already mixed — has no convention to preserve, and text is then written exactly as handed
 * over rather than normalised on a guess.
 */
private enum class Convention {
  Lf, Crlf, Unknown;

  /** [text] in this file's own convention, with its own trailing-newline habit left to the caller. */
  fun render(text: String): String = when (this) {
    Lf -> text.replace("\r\n", "\n")
    Crlf -> text.replace("\r\n", "\n").replace("\n", "\r\n")
    Unknown -> text
  }

  companion object {
    fun of(existing: ByteArray?): Convention {
      if (existing == null) return Unknown
      var lf = 0
      var crlf = 0
      for (index in existing.indices) {
        if (existing[index] != NEWLINE) continue
        if (index > 0 && existing[index - 1] == CARRIAGE_RETURN) crlf++ else lf++
      }
      return when {
        crlf > 0 && lf == 0 -> Crlf
        lf > 0 && crlf == 0 -> Lf
        // No newline at all, or both kinds already: there is nothing here to preserve.
        else -> Unknown
      }
    }
  }
}

/**
 * The file's trailing-newline habit, kept: a file that ended with one still does, and a file
 * that did not still does not. Applied to a whole write only — an edit changes the region it
 * matched and nothing else, including the last byte.
 */
private fun Convention.withTrailingNewlineOf(existing: ByteArray?, text: String): String {
  if (existing == null || existing.isEmpty()) return text
  val ending = if (this == Convention.Crlf) "\r\n" else "\n"
  val had = existing.last() == NEWLINE
  val has = text.endsWith("\n")
  return when {
    had && !has -> text + ending
    // Exactly the one ending the file does without, never every blank line at its end.
    !had && has -> text.removeSuffix(ending)
    else -> text
  }
}

/**
 * Temp file beside the target, then an atomic rename.
 *
 * The two halves answer differently on purpose. Everything before the rename leaves the target
 * exactly as it was, so a failure there is an ordinary `failed` — nothing changed, safe to
 * retry. The rename itself is where that guarantee ends: an [IOException] from it goes up to
 * the pipeline, which shapes a mutation's broken I/O as **Uncertain**.
 */
private fun Target.land(bytes: ByteArray, created: Boolean): Outcome<FileWritten> {
  val temporary = try {
    stage(file, bytes)
  } catch (failure: IOException) {
    val detail = failure.message ?: failure.toString()
    return Outcome.Failed(
      Failure.IoError(workspace.name, detail),
      "This Operation failed and changed nothing: '$path' could not be written: $detail",
    )
  }
  try {
    Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
  } catch (failure: Throwable) {
    Files.deleteIfExists(temporary)
    throw failure
  }
  // The rename is a change to the directory, and an atomic rename is not by itself a durable
  // one: without this, a power loss can take a landed write with it.
  try {
    FileChannel.open(file.parent, READ).use { it.force(true) }
  } catch (_: IOException) {
    // Not every filesystem lets a directory be opened this way; the rename still stands.
  }
  return Outcome.Ok(FileWritten(path, bytes.size, created))
}

/**
 * The replacement, written and flushed, waiting beside the target for its rename. It is created
 * with the account's ordinary default mode rather than a temp file's 0600, so a file ChatGPT
 * creates is one the user's own tools can read; when the target already exists, its own bits
 * are copied over that instead.
 */
private fun stage(target: Path, bytes: ByteArray): Path {
  val directory = target.parent
  var temporary: Path
  while (true) {
    temporary = directory.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
    try {
      Files.createFile(temporary)
      break
    } catch (_: FileAlreadyExistsException) {
      // A name in use is not a failure; the next one will not be.
    }
  }
  try {
    Files.write(temporary, bytes)
    FileChannel.open(temporary, WRITE).use { it.force(true) }
    try {
      if (Files.exists(target)) Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target))
    } catch (_: UnsupportedOperationException) {
      // Not a POSIX filesystem, so there are no bits of the target's to keep.
    }
  } catch (failure: Throwable) {
    Files.deleteIfExists(temporary)
    throw failure
  }
  return temporary
}
