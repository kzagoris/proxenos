package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Resolve against the Root and confine to it: symlinks are followed and *then* checked, `..`
 * traversal and absolute paths rejected. A rejection names the symlink that caused
 * it, so a legitimate one reads as fixable — the remedy is to register its target as its own
 * Workspace.
 *
 * `run_command`'s command is bounded by none of this, but its optional `cwd` is confined here
 * like any other path argument: the Root stops confining what a command does, not where
 * the Runtime starts it.
 *
 * What this cannot promise is that the path it returns still names what it checked. Between the
 * check and the open, another process may replace a directory with a symlink. Closing that would
 * take `openat` semantics the JDK does not expose; the design prescribes resolve-then-check, and
 * a command at Command level is already wider than the Root.
 */
internal fun confine(workspace: Workspace, argument: String): Outcome<Path> = try {
  confining(workspace, argument)
} catch (failure: IOException) {
  // Confinement is itself I/O — a path can vanish between the two syscalls that resolve it —
  // and an Operation-level problem is a result, never an exception out of the core.
  Outcome.Failed(
    Failure.IoError(workspace.name, failure.message ?: failure.toString()),
    "'$argument' could not be resolved: ${failure.message ?: failure.toString()}",
  )
}

private fun confining(workspace: Workspace, argument: String): Outcome<Path> {
  val requested = try {
    Path.of(argument)
  } catch (_: InvalidPathException) {
    return Outcome.Failed(Failure.InvalidArgument("path"), "'$argument' is not a usable path.")
  }
  val rootReal = try {
    Path.of(workspace.root).toRealPath()
  } catch (_: IOException) {
    return Outcome.Failed(
      Failure.WorkspaceBroken(workspace.name),
      "Workspace '${workspace.name}' is Broken; re-confirm its Root.",
    )
  }
  if (requested.isAbsolute) return outside(
    workspace, argument, null,
    "'$argument' is an absolute path. Paths are resolved against the Root of Workspace " +
      "'${workspace.name}', so name this one relative to it.",
  )
  // Confinement rejects `..` traversal as such, not only the traversal that happens to end up outside.
  // A path inside the Root never needs one, and the rule is worth more than the convenience.
  if (requested.any { it.toString() == ".." }) return outside(
    workspace, argument, null,
    "'$argument' traverses with '..'. Name a path relative to the Root of Workspace " +
      "'${workspace.name}' without it.",
  )
  val real = realPath(rootReal.resolve(requested))
  if (real.startsWith(rootReal)) return Outcome.Ok(real)
  val link = escapingSymlink(rootReal, requested)
  return outside(
    workspace, argument, link?.name,
    if (link == null) "'$argument' reaches outside the Root of Workspace '${workspace.name}'."
    else "'$argument' leaves the Root of Workspace '${workspace.name}' through the symlink " +
      "'${link.name}', which points at '${link.target}'. To reach that directory, register it " +
      "as its own Workspace.",
  )
}

private fun outside(workspace: Workspace, argument: String, symlink: String?, message: String) =
  Outcome.Failed(Failure.OutsideRoot(workspace.name, argument, symlink), message)

/**
 * The real path of something that need not exist yet: the deepest existing ancestor is resolved
 * for real, and what is left is resolved textually onto it, so a write to a path that does not
 * exist is confined by the rule that governs a read of one that does.
 *
 * A **dangling** symlink is the case worth naming. `Files.exists` follows links, so a link to a
 * file that is not there reports that the link itself is not there — and resolving it textually
 * would hand back a path inside the Root that writes outside it. Its target is followed instead.
 */
private fun realPath(path: Path, followed: Int = 0): Path {
  var existing = path
  while (!Files.exists(existing)) {
    if (Files.isSymbolicLink(existing)) {
      // Bounded the way the kernel bounds it: a link that resolves to itself is not a hang.
      if (followed >= MAX_SYMLINKS) return existing.normalize()
      val target = existing.parent.resolve(Files.readSymbolicLink(existing)).normalize()
      return realPath(target.resolve(existing.relativize(path)), followed + 1)
    }
    existing = existing.parent ?: return path.normalize()
  }
  return existing.toRealPath().resolve(existing.relativize(path)).normalize()
}

/** The first component of the argument that is a symlink pointing out of the Root. */
private fun escapingSymlink(rootReal: Path, requested: Path): EscapingLink? {
  var prefix = rootReal
  for (name in requested) {
    prefix = prefix.resolve(name)
    if (!Files.isSymbolicLink(prefix)) continue
    val target = try {
      prefix.toRealPath()
    } catch (_: IOException) {
      // A dangling link has no real path; where it points is still what confinement judged.
      prefix.parent.resolve(Files.readSymbolicLink(prefix)).normalize()
    }
    val label = if (prefix.startsWith(rootReal)) rootReal.relativize(prefix) else prefix
    if (!target.startsWith(rootReal)) return EscapingLink(label.toString(), target.toString())
    prefix = target
  }
  return null
}

private data class EscapingLink(val name: String, val target: String)

private const val MAX_SYMLINKS = 40
