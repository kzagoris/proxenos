package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
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

/**
 * One byte-exact replacement that must match exactly once. Zero matches and several are
 * both a `failed` that changed nothing — several names the count rather than taking the first,
 * because a model that meant one of them is better told than guessed at.
 *
 * The uniqueness requirement *is* the concurrency check: another process that changed the
 * region takes the match with it. That is why nothing here carries an mtime or a hash
 * precondition, which would be ambient state carried between calls.
 */
internal fun editFile(op: Operation.EditFile, workspace: Workspace, file: Path): Outcome<FileWritten> {
  val target = Target(workspace, op.path, file)
  if (op.oldText.isEmpty()) return Outcome.Failed(
    Failure.InvalidArgument("old_text"),
    "This Operation failed and changed nothing: old_text is empty, and an empty string occurs " +
      "everywhere in every file. Quote the text to replace.",
  )
  val existing = target.readExisting().valueOr { problem -> return problem } ?: return target.notAFile()
  // A byte match inside a file that is not text is a coincidence, not an edit, and writing the
  // result back would be a rewrite of something nobody read.
  if (existing.contains(ZERO)) return Outcome.Failed(
    Failure.Binary(workspace.name, op.path),
    "This Operation failed and changed nothing: '${op.path}' is a binary file, so there is no " +
      "text to edit.",
  )

  // Byte for byte, with nothing normalised on the way in: text quoted back from
  // `read_file` already carries the file's own line endings and matches as it stands, and a
  // quote that does not match is a `failed` that changed nothing rather than a near-enough
  // edit. What the file's convention governs is the replacement, below.
  val convention = Convention.of(existing)
  val old = op.oldText.toByteArray(UTF_8)
  val occurrences = occurrencesOf(existing, old)
  if (occurrences.isEmpty()) return Outcome.Failed(
    Failure.NoMatch(workspace.name, op.path),
    "This Operation failed and changed nothing: old_text does not occur in '${op.path}'. It is " +
      "matched byte for byte, line endings included, and if another process has changed that " +
      "region it no longer matches — read the file again and edit what is there now.",
  )
  if (occurrences.size > 1) return Outcome.Failed(
    Failure.SeveralMatches(workspace.name, op.path, occurrences.size),
    "This Operation failed and changed nothing: old_text occurs ${occurrences.size} times in " +
      "'${op.path}', and an edit must match exactly once. Quote enough surrounding text to name " +
      "the one you mean.",
  )

  val at = occurrences.single()
  val new = convention.render(op.newText).toByteArray(UTF_8)
  val bytes = existing.copyOfRange(0, at) + new + existing.copyOfRange(at + old.size, existing.size)
  return target.land(bytes, created = false)
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
