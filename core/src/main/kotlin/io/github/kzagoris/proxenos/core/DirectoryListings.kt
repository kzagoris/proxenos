package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * One directory, sorted, bounded by the byte cap. The confinement is the pipeline's
 * — a directory reached through a symlink out of the Root never arrives here — so what is left
 * is the listing itself and the promise that what it left out is counted rather than silent.
 */
internal fun listDirectory(
  op: Operation.ListDirectory,
  workspace: Workspace,
  directory: Path,
): Outcome<DirectoryListing> {
  notADirectory(workspace, op.label, directory)?.let { return it }

  val names = Files.newDirectoryStream(directory).use { stream ->
    stream.map { it.fileName.toString() }
  }.sorted()

  // Measured on the names, which is what varies: a kind and a size are a fixed handful of bytes
  // each, and a directory overflows the cap with filenames or not at all.
  val bounded = headAndTail(names, Operation.OUTPUT_HEAD_BYTES, Operation.OUTPUT_TAIL_BYTES) {
    it.utf8Size()
  }
  val entries = bounded.kept.map { describe(directory.resolve(it), it) }
  return Outcome.Ok(DirectoryListing(op.label, entries, names.size, bounded.droppedBytes))
}

/**
 * Shared by the two tools that take a directory, so that "not a directory" and "not there at
 * all" are told apart once rather than by each of them in slightly different words.
 */
internal fun notADirectory(workspace: Workspace, label: String, directory: Path): Outcome.Failed? {
  if (Files.isDirectory(directory)) return null
  return Outcome.Failed(
    Failure.NotADirectory(workspace.name, label),
    if (Files.exists(directory)) "'$label' is not a directory."
    else "'$label' does not exist in Workspace '${workspace.name}'.",
  )
}

/**
 * A symlink is named as one rather than as what it points at. Following it here would make a
 * listing of a directory full of links into a second, unconfined resolution — and the link's
 * target is a path the caller can ask about itself.
 */
private fun describe(path: Path, name: String): DirectoryEntry {
  val attributes = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  } catch (_: IOException) {
    // It was there a moment ago when the directory was read. What it is now is unknowable, and
    // one vanished entry is not a reason to fail the whole listing.
    return DirectoryEntry(name, EntryKind.Other, null)
  }
  val kind = when {
    attributes.isSymbolicLink -> EntryKind.Symlink
    attributes.isDirectory -> EntryKind.Directory
    attributes.isRegularFile -> EntryKind.File
    else -> EntryKind.Other
  }
  return DirectoryEntry(name, kind, if (kind == EntryKind.File) attributes.size() else null)
}
