package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.coreapi.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.*

/** All constraints, including nested ones, are owned by the core catalog. */
internal fun OperationSpec.inputSchema(): ToolSchema = objectSchema(arguments)
internal fun OperationSpec.outputSchema(): ToolSchema? = output?.let { objectSchema(it.properties, it.definitions) }
internal fun OperationSpec.toolAnnotations() = ToolAnnotations(
  readOnlyHint = annotations.readOnly, destructiveHint = annotations.destructive, openWorldHint = annotations.openWorld,
)

private fun objectSchema(arguments: List<ArgumentSpec>, definitions: List<ArgumentSpec> = emptyList()) = ToolSchema(
  properties = buildJsonObject { arguments.forEach { put(it.name, it.schema()) } },
  required = arguments.filter { it.required }.map { it.name },
  defs = if (definitions.isEmpty()) null else buildJsonObject { definitions.forEach { put(it.name, it.schema()) } },
)

private fun ArgumentSpec.schema(): JsonObject = buildJsonObject {
  if (reference != null) put("$" + "ref", reference)
  else if (alternatives.isNotEmpty()) put("anyOf", JsonArray(alternatives.map { it.schema() }))
  else {
    val kind = when (type) {
      ArgumentType.Text -> "string"
      ArgumentType.Integer -> "integer"
      ArgumentType.Flag -> "boolean"
      ArgumentType.Array -> "array"
      ArgumentType.Object -> "object"
      ArgumentType.Number -> "number"
    }
    put("type", if (nullable) JsonArray(listOf(JsonPrimitive(kind), JsonPrimitive("null"))) else JsonPrimitive(kind))
    if (type == ArgumentType.Object) {
      put("properties", buildJsonObject { properties.forEach { put(it.name, it.schema()) } })
      put("required", JsonArray(properties.filter { it.required }.map { JsonPrimitive(it.name) }))
    }
  }
  if (description.isNotEmpty()) put("description", description)
  items?.let { put("items", it.schema()) }
  if (enumValues.isNotEmpty()) put("enum", JsonArray(enumValues.map(::JsonPrimitive)))
  minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }
  minItems?.let { put("minItems", it) }; maxItems?.let { put("maxItems", it) }
}
