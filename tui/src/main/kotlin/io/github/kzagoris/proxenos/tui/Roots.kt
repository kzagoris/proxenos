package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.Workspace
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import java.io.IOException
import java.nio.file.Path

/**
 * A Root as typed, made absolute here. The Runtime resolves a relative Root against its own
 * working directory, which is not this terminal's, so a relative path sent as typed would
 * register some other directory.
 */
fun absoluteRoot(typed: String, home: Path = Path.of(System.getProperty("user.home"))): Path {
  val text = typed.trim()
  val expanded = when {
    text == "~" -> home
    text.startsWith("~/") -> home.resolve(text.removePrefix("~/"))
    else -> Path.of(text)
  }
  return expanded.toAbsolutePath().normalize()
}

/** How a Root being registered overlaps one already registered. */
enum class Relation { Same, Inside, Contains }

data class Overlap(val workspace: Workspace, val relation: Relation)

/**
 * Every registered Workspace whose Root overlaps [root]: the same directory, one inside it, or
 * one it sits inside. Compared as real paths where they resolve, so a symlink to a registered
 * Root is seen for the same directory it is.
 */
fun overlaps(root: Path, workspaces: List<WorkspaceState>): List<Overlap> {
  val candidate = real(root)
  return workspaces.mapNotNull { state ->
    val existing = real(Path.of(state.workspace.root))
    val relation = when {
      candidate == existing -> Relation.Same
      candidate.startsWith(existing) -> Relation.Inside
      existing.startsWith(candidate) -> Relation.Contains
      else -> return@mapNotNull null
    }
    Overlap(state.workspace, relation)
  }
}

private fun real(path: Path): Path = try {
  path.toRealPath()
} catch (_: IOException) {
  path.toAbsolutePath().normalize()
}
