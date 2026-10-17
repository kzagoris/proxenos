package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val LISTING_BYTES = 12 * 1024
private val listingJson = Json { encodeDefaults = true }

/**
 * One consecutive page of a sorted directory. The confinement is the pipeline's
 * — a directory reached through a symlink out of the Root never arrives here — so what is left
 * is the listing itself and the promise that what it left out is counted rather than silent.
 */
internal fun listDirectory(
  op: Operation.ListDirectory,
  workspace: Workspace,
  directory: Path,
): Outcome<DirectoryListing> {
  if (op.limit !in 1..1000) return Outcome.Failed(
    Failure.InvalidArgument("limit"), "limit must be between 1 and 1000.",
  )
  if (op.after?.any { it == '/' || it == '\u0000' } == true) return Outcome.Failed(
    Failure.InvalidArgument("after"), "after must be an entry name, without '/' or NUL.",
  )
  notADirectory(workspace, op.label, directory)?.let { return it }
  val metadata = DirectoryListing(op.label, emptyList(), Int.MAX_VALUE, Int.MAX_VALUE, true)
  if (listingJson.encodeToString(metadata).utf8Size() > LISTING_BYTES) return Outcome.Failed(
    Failure.InvalidArgument("path"), "The directory path exceeds the response budget.",
  )

  val names = Files.newDirectoryStream(directory).use { stream ->
    stream.map { it.fileName.toString() }
  }.sorted()

  val after = op.after
  val remaining = names.filter { after == null || it > after }
  val entries = mutableListOf<DirectoryEntry>()
  var cappedByBytes = false
  for (name in remaining.take(op.limit)) {
    entries += describe(directory.resolve(name), name)
    // Reserve the full metadata as well as JSON escaping: filenames containing control
    // characters can cost six times their on-disk bytes. The adapter also carries readable
    // compatibility text, so leave most of its complete-response budget available.
    val candidate = DirectoryListing(op.label, entries, names.size, Int.MAX_VALUE, true, name)
    if (listingJson.encodeToString(candidate).utf8Size() > LISTING_BYTES) {
      entries.removeAt(entries.lastIndex)
      cappedByBytes = true
      break
    }
  }
  val incomplete = entries.size < remaining.size
  if (cappedByBytes && entries.isEmpty()) return Outcome.Failed(
    Failure.InvalidArgument("path"), "The directory path leaves no room for an entry within the response budget.",
  )
  val droppedBytes = if (cappedByBytes) remaining.drop(entries.size)
    .sumOf { it.utf8Size().toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
  return Outcome.Ok(DirectoryListing(
    op.label, entries, names.size, droppedBytes, incomplete,
    if (incomplete) entries.lastOrNull()?.name else null,
  ))
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
