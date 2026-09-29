package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.coreapi.ArgumentSpec
import io.github.kzagoris.proxenos.coreapi.ArgumentType
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The catalog, rendered (SPEC §3). The core owns the eleven as data — name, description,
 * arguments, required Access Level — and this file turns that data into the JSON Schema the
 * SDK publishes. It reads every entry the same way, so a catalog entry added in the core
 * changes what ChatGPT enumerates with nothing edited here.
 *
 * Nothing about flatness, the mandatory `workspace` argument or the static shape is decided
 * here. They are domain decisions (§3), and an adapter that re-decided them is exactly the
 * second adapter that could silently violate them.
 */
internal fun OperationSpec.inputSchema(): ToolSchema = ToolSchema(
  properties = buildJsonObject { arguments.forEach { put(it.name, it.schema()) } },
  // Required means required at the schema level too, so a client that validates before
  // sending refuses a scoped call with no Workspace rather than sending one that cannot route.
  required = arguments.filter { it.required }.map { it.name },
)

private fun ArgumentSpec.schema(): JsonObject = buildJsonObject {
  put("type", type.jsonType)
  // The description is carried through untouched. On the three mutating entries it is the
  // §6.4 wording, which is the only lever on a repeat the model initiates itself: an adapter
  // that trimmed or rephrased it would be editing the one instruction that limits replays.
  put("description", description)
}

private val ArgumentType.jsonType: String
  get() = when (this) {
    ArgumentType.Text -> "string"
    ArgumentType.Integer -> "integer"
    ArgumentType.Flag -> "boolean"
  }
