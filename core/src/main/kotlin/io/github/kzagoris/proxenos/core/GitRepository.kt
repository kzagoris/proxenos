package io.github.kzagoris.proxenos.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Whether a directory is a Git repository, read from Git's own on-disk layout rather than by
 * asking the `git` executable — discovery is an attribute of a Root (SPEC §2.1) and must not
 * depend on an optional program being installed.
 *
 * https://git-scm.com/docs/gitrepository-layout
 */
internal fun gitRepositoryAt(directory: Path): Boolean = try {
  val marker = directory.resolve(".git")
  val metadata = when {
    Files.isDirectory(marker) -> marker
    Files.isRegularFile(marker) -> {
      // A worktree or a submodule: the marker is a file pointing at the real metadata.
      val pointer = Files.readString(marker).trimEnd()
      if (pointer.startsWith("gitdir: ")) directory.resolve(pointer.removePrefix("gitdir: ")) else null
    }
    else -> directory // A bare repository has its metadata directly in the directory.
  }
  metadata != null && looksLikeMetadata(metadata)
} catch (_: IOException) {
  false
} catch (_: InvalidPathException) {
  false
}

/**
 * The repository [from] sits in, or null. Search needs this to tell two situations apart that a
 * failed `git ls-files` cannot: no repository here, and a repository whose ignore rules could
 * not be read. Walking the second would hand back files the project deliberately ignores.
 */
internal fun enclosingGitRepository(from: Path): Path? {
  var directory: Path? = from
  while (directory != null) {
    if (gitRepositoryAt(directory)) return directory
    directory = directory.parent
  }
  return null
}

private fun looksLikeMetadata(metadata: Path): Boolean {
  val commonFile = metadata.resolve("commondir")
  val common = if (Files.isRegularFile(commonFile)) {
    metadata.resolve(Files.readString(commonFile).trimEnd())
  } else metadata
  return Files.isRegularFile(metadata.resolve("HEAD")) &&
    Files.isDirectory(common.resolve("objects")) &&
    Files.isDirectory(common.resolve("refs"))
}
