package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Components are created separately so an interrupted tree reports the effects already known. */
internal fun createDirectory(op: Operation.CreateDirectory, workspace: Workspace, file: Path): Outcome<DirectoryCreated> {
  val created = mutableListOf<String>()
  fun failed(reason: Failure, detail: String): Outcome<Nothing> = if (created.isEmpty()) {
    Outcome.Failed(reason, "This Operation failed and changed nothing: $detail")
  } else {
    Outcome.Uncertain(
      Uncertainty.DirectoryCreationIncomplete(detail),
      "Directory creation did not complete: $detail; ${Outcome.Uncertain.EFFECTS_UNCERTAIN}. " +
        "Known created directories: ${created.joinToString()}. They were left in place.",
      createdPaths = created.toList(),
    )
  }
  try {
    val root = Path.of(workspace.root).toRealPath()
    val relative = root.relativize(file)
    val components = if (op.parents) relative.mapIndexed { index, _ -> relative.subpath(0, index + 1) }
    else listOf(relative)
    // Every created component must fit in both the answer and a possible Uncertain warning.
    // Refuse before effects rather than creating a tree whose effects cannot be reported.
    if (Json.encodeToString(components.map { it.toString() }).utf8Size() +
      Json.encodeToString(op.path).utf8Size() > 8 * 1024) return Outcome.Failed(
      Failure.InvalidArgument("path"), "This Operation failed and changed nothing: the directory components exceed the effect-reporting budget; create a shorter prefix first.",
    )
    for (component in components) {
      val label = component.toString().ifEmpty { Operation.THIS_DIRECTORY }
      val confined = when (val checked = confine(workspace, label)) {
        is Outcome.Ok -> checked.value
        is Outcome.Failed -> return failed(checked.reason, checked.message)
        is Outcome.Uncertain -> return checked.copy(createdPaths = created.toList())
      }
      if (Files.isDirectory(confined)) continue
      try {
        Files.createDirectory(confined)
        created += label
      } catch (_: FileAlreadyExistsException) {
        // Another creator may have won this component after the check above.
        if (!Files.isDirectory(confined)) return failed(
          Failure.NotADirectory(workspace.name, label), "'$label' exists and is not a directory.",
        )
      }
    }
    return Outcome.Ok(DirectoryCreated(op.path, created.toList(), noOp = created.isEmpty()))
  } catch (failure: IOException) {
    val detail = failure.message ?: failure.toString()
    return failed(Failure.IoError(workspace.name, detail), detail)
  }
}
