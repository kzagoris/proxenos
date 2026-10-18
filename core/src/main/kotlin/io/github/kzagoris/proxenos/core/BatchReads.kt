package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

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
      val room = BATCH_PAYLOAD_BYTES - batchJson.encodeToString(results).utf8Size() - (op.files.size - results.size) * 256
      val outcome = try {
        when (val confined = confine(workspace, item.path)) {
          is Outcome.Ok -> readFile(Operation.ReadFile(op.workspace, item.path, item.offset, item.limit, item.byteOffset), workspace, confined.value,
            // readFile already budgets escaped UTF-8; discounting it again wastes most of a reply.
            byteCap = minOf(16 * 1024, maxOf(8, room - JsonPrimitive(item.path).toString().utf8Size() - 512)))
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
      if (batchJson.encodeToString(results + result).utf8Size() > BATCH_PAYLOAD_BYTES - op.files.size * 256) {
        results += pending
        exhausted = true
      } else results += result
    }
  }
  return Outcome.Ok(BatchRead(results, exhausted || results.any { it.content?.nextOffset != null }))
}

// At most two escaped copies reach MCP (structured and readable). The remainder of its
// 63 KiB budget covers readable headers, default fields and the recorded-Delivery note.
private const val BATCH_PAYLOAD_BYTES = 24 * 1024
private val batchJson = Json { encodeDefaults = true }
