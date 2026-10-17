package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.coreapi.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private val wire = Json { encodeDefaults = true }

internal fun structured(operation: Operation<*>, outcome: Outcome<*>): JsonObject = buildJsonObject {
  val value = (outcome as? Outcome.Ok)?.value
  put("status", when {
    outcome is Outcome.Failed -> "failed"
    outcome is Outcome.Uncertain -> "uncertain"
    value is CommandReply.Promoted -> "promoted"
    value is Collected.StillRunning -> "running"
    value is Collected.Reached -> "reached"
    value is Collected.Recorded -> "recorded"
    else -> "ok"
  })
  put("recorded", outcome.recorded)
  put("incomplete", false)
  when (outcome) {
    is Outcome.Failed -> {
      put("message", outcome.message)
      put("conflicts", wire.encodeToJsonElement(outcome.conflicts))
    }
    is Outcome.Uncertain -> {
      put("message", outcome.message)
      put("createdPaths", wire.encodeToJsonElement(outcome.createdPaths))
    }
    is Outcome.Ok -> put("result", result(operation, value))
  }
}

@Suppress("UNCHECKED_CAST") // Operation fixes the type of its result.
private fun result(op: Operation<*>, value: Any?): JsonElement = when (op) {
  Operation.ListWorkspaces -> wire.encodeToJsonElement(value as List<WorkspaceListing>)
  is Operation.ReadFile -> wire.encodeToJsonElement(value as FileContent)
  is Operation.ReadFiles -> wire.encodeToJsonElement(value as BatchRead)
  is Operation.ListDirectory -> wire.encodeToJsonElement(value as DirectoryListing)
  is Operation.Search -> wire.encodeToJsonElement(value as SearchResults)
  is Operation.GitStatus -> wire.encodeToJsonElement(value as GitStatusReport)
  is Operation.GitDiff -> wire.encodeToJsonElement(value as GitDiffReport)
  is Operation.GitLog -> wire.encodeToJsonElement(value as GitLogReport)
  is Operation.WriteFile, is Operation.EditFile -> wire.encodeToJsonElement(value as FileWritten)
  is Operation.CreateDirectory -> wire.encodeToJsonElement(value as DirectoryCreated)
  is Operation.RunCommand -> wire.encodeToJsonElement(value as CommandReply)
  is Operation.GetResult -> wire.encodeToJsonElement(value as Collected)
}

/** Count the actual SDK envelope: escaping and compatibility text consume the same budget. */
internal fun boundedReply(body: String, isError: Boolean, structured: JsonObject): CallToolResult {
  val recorded = structured["recorded"]?.jsonPrimitive?.booleanOrNull == true
  val recordedNote = "\n\n${Outcome.RECORDED_RESULT}"
  val original = if (recorded) body.removeSuffix(recordedNote) else body
  fun make(text: String, data: JsonObject, includeNote: Boolean = recorded) = CallToolResult(
    listOf(TextContent(text + if (includeNote) recordedNote else "")), isError,
    JsonObject(data + ("recorded" to JsonPrimitive(includeNote))),
  )
  // Make the same sizing decision for every Delivery, reserving its readable note on the
  // first too. A repeat must begin with precisely the answer the first Delivery carried.
  fun fits(text: String, data: JsonObject) = wire.encodeToString(make(text, data, includeNote = true))
    .toByteArray().size <= REPLY_BYTES
  if (fits(original, structured)) return make(original, structured)
  var data = JsonObject(structured + ("incomplete" to JsonPrimitive(true)))
  data["result"]?.let { data = JsonObject(data + ("result" to trimOutput(it))) }
  val note = "\n\nOutput truncated by the complete serialized reply budget; narrow the range, subtree or paths for omitted output."
  if (fits(original + note, data)) return make(original + note, data)
  val minimum = if (isError) original + note else original.substringBefore("\n\n") + note
  if (fits(minimum, data)) return make(minimum, data)
  data = JsonObject(data - "result")
  if (fits(minimum, data)) return make(minimum, data)
  // Safety can belong to a collected Lost/Uncertain outcome rather than the outer Outcome.
  val safety = if (Outcome.Uncertain.EFFECTS_UNCERTAIN in original)
    "\n${Outcome.Uncertain.EFFECTS_UNCERTAIN}." else ""
  val warning = "Reply metadata exceeded the serialized budget; details were omitted.$safety"
  data = JsonObject(data - "message" + ("message" to JsonPrimitive(warning)))
  if (!fits(warning + note, data)) data = JsonObject(data - "createdPaths" - "conflicts")
  check(fits(warning + note, data)) { "The fixed reply envelope exceeds its budget" }
  return make(warning + note, data)
}

/** Commands retain both ends in Activity; the wire names every byte it omits here. */
private fun trimOutput(value: JsonElement): JsonElement = when (value) {
  is JsonObject -> {
    val fields = value.mapValues { (_, child) -> trimOutput(child) }.toMutableMap()
    var dropped = 0
    for (name in listOf("output", "outputSoFar", "patch", "diff")) {
      val original = (value[name] as? JsonPrimitive)?.content ?: continue
      val kept = original.take(2048)
      dropped += original.toByteArray().size - kept.toByteArray().size
      fields[name] = JsonPrimitive(kept)
      if (name == "diff" && kept != original) fields["diffTruncated"] = JsonPrimitive(true)
    }
    if (dropped > 0 && "droppedBytes" in fields) fields["droppedBytes"] = JsonPrimitive(
      (fields["droppedBytes"]?.jsonPrimitive?.intOrNull ?: 0) + dropped,
    )
    JsonObject(fields)
  }
  is JsonArray -> JsonArray(value.map(::trimOutput))
  else -> value
}

// Leave space for JSON-RPC framing, which the SDK adds outside CallToolResult.
private const val REPLY_BYTES = 63 * 1024
