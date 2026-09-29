package io.github.kzagoris.proxenos.core

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlin.text.Charsets.UTF_8

/** Replaces [file] with [text], written whole or not at all, and durably: flushed, renamed over it, and the rename flushed. */
internal fun writeDurably(file: Path, text: String) {
  val destination = file.toAbsolutePath()
  Files.createDirectories(destination.parent)
  val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}-", ".tmp")
  try {
    Files.writeString(temporary, text, UTF_8)
    FileChannel.open(temporary, WRITE).use { it.force(true) }
    Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    // The rename is a change to the directory, and an atomic rename is not by itself a durable
    // one: without this, a power loss can take the new contents with it.
    try {
      FileChannel.open(destination.parent, READ).use { it.force(true) }
    } catch (_: IOException) {
      // Not every filesystem lets a directory be opened this way; the rename still stands.
    }
  } finally {
    Files.deleteIfExists(temporary)
  }
}
