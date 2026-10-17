package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Admission resolves the Workspace once; each range is confined here, independently. */
internal fun readFiles(op: Operation.ReadFiles, workspace: Workspace): Outcome<BatchRead> {
  if (op.files.size !in 1..20) return Outcome.Failed(Failure.InvalidArgument("files"), "files must contain 1-20 ranges.")
  val results = mutableListOf<FileReadResult>()
  var exhausted = false
  op.files.forEach { item ->
    val pending = FileReadResult("not_processed", message = "Shared output budget exhausted; read this range separately.")
    if (exhausted) {
      results += pending
    } else {
      val room = BATCH_PAYLOAD_BYTES - Json.encodeToString(results).utf8Size() - (op.files.size - results.size) * 256
      val outcome = try {
        when (val confined = confine(workspace, item.path)) {
          is Outcome.Ok -> readFile(Operation.ReadFile(op.workspace, item.path, item.offset, item.limit, item.byteOffset), workspace, confined.value,
            byteCap = minOf(4096, maxOf(8, (room - item.path.utf8Size() * 6 - 512) / 6)))
          is Outcome.Failed -> confined
          is Outcome.Uncertain -> confined
        }
      } catch (failure: IOException) {
        Outcome.Failed(Failure.IoError(workspace.name, failure.toString()), "Could not read item: ${failure.message}")
      }
      val result = when (outcome) {
        is Outcome.Ok -> FileReadResult("ok", content = outcome.value)
        is Outcome.Failed -> FileReadResult("failed", message = outcome.message)
        is Outcome.Uncertain -> FileReadResult("failed", message = outcome.message)
      }
      // Leave space for every remaining status and compatibility text as well as escaped JSON.
      if (Json.encodeToString(results + result).utf8Size() > BATCH_PAYLOAD_BYTES - op.files.size * 256) {
        results += pending
        exhausted = true
      } else results += result
    }
  }
  return Outcome.Ok(BatchRead(results, exhausted || results.any { it.content?.nextOffset != null }))
}

private const val BATCH_PAYLOAD_BYTES = 12 * 1024
