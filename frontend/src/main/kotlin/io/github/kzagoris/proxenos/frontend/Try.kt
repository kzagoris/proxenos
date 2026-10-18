package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.coreapi.*
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * What a TryOperation asks for: everything but the Workspace, which is the one it is tried
 * against, and the `request_id`, which is minted.
 */
fun askedOf(spec: OperationSpec): List<ArgumentSpec> =
  spec.arguments.filter { it.name != Operation.WORKSPACE_ARGUMENT && it.name != Operation.KEY_ARGUMENT }.map {
    when (it.type) {
      ArgumentType.Array -> it.copy(description = it.description + " Enter a JSON array.")
      ArgumentType.Object -> it.copy(description = it.description + " Enter a JSON object.")
      else -> it
    }
  }

/**
 * A TryOperation's Operation, from the text typed for each argument. A frontend is an adapter
 * like the `mcp` one: it builds an Operation or refuses to, and what the Operation then does —
 * including every Access Level refusal — is the one pipeline's to say.
 *
 * A mutating one is given a fresh `request_id` of its own, like any other caller: a try is an
 * intentional new execution every time. It starts with [caller] (`tui`, `gui`), the frontend
 * that minted it.
 *
 * @throws IllegalArgumentException naming the argument that is not what the catalog says it is.
 */
fun operationFrom(caller: String, tool: String, workspace: String, given: Map<String, String>): Operation<*> {
  fun text(name: String): String? = given[name]
  fun required(name: String): String = text(name) ?: throw IllegalArgumentException("'$name' is required.")
  fun number(name: String): Int? = text(name)?.let { it.trim().toIntOrNull() ?: throw IllegalArgumentException("'$name' must be a whole number, not '$it'.") }
  fun flag(name: String): Boolean = when (text(name)?.trim()?.lowercase()) {
    null, "", "n", "no", "false" -> false
    "y", "yes", "true" -> true
    else -> throw IllegalArgumentException("'$name' is yes or no, not '${text(name)}'.")
  }
  fun array(name: String): JsonArray? = text(name)?.let {
    Json.parseToJsonElement(it) as? JsonArray ?: throw IllegalArgumentException("'$name' must be a JSON array.")
  }
  fun strings(name: String): List<String> = array(name)?.map { item ->
    require(item is JsonPrimitive && item.isString) { "'$name' must contain text values." }
    item.content
  }.orEmpty()
  fun objects(name: String): List<JsonObject>? = array(name)?.map { item ->
    item as? JsonObject ?: throw IllegalArgumentException("'$name' must contain objects.")
  }
  fun field(item: JsonObject, name: String): String? = when (val value = item[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> {
      require(value.isString) { "'$name' must be text." }
      value.content
    }
    else -> throw IllegalArgumentException("'$name' must be text.")
  }
  fun requiredField(item: JsonObject, name: String): String = field(item, name)
    ?: throw IllegalArgumentException("'$name' is required in each item.")
  fun integer(item: JsonObject, name: String): Int? = when (val value = item[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.content.toIntOrNull() ?: throw IllegalArgumentException("'$name' must be a whole number.")
    else -> throw IllegalArgumentException("'$name' must be a whole number.")
  }
  val key = "$caller-${UUID.randomUUID()}"
  return when (tool) {
    "list_workspaces" -> Operation.ListWorkspaces
    "list_directory" -> Operation.ListDirectory(workspace, text("path"), text("after"), number("limit") ?: 1000)
    "read_file" -> Operation.ReadFile(workspace, required("path"), number("offset"), number("limit"), number("byte_offset") ?: 0)
    "export_file" -> Operation.ExportFile(workspace, required("path"), flag("vision"))
    "import_attachment" -> {
      val file = try { Json.parseToJsonElement(required("file")) as? JsonObject }
      catch (_: IllegalArgumentException) { null }
      requireNotNull(file) { "'file' must be a JSON object containing a genuine host attachment reference." }
      Operation.ImportAttachment(workspace, required("path"), AttachmentReference(
        requiredField(file, "download_url"), requiredField(file, "file_id"), field(file, "mime_type"), field(file, "file_name")), key)
    }
    "read_files" -> Operation.ReadFiles(workspace, (objects("files") ?: throw IllegalArgumentException("'files' is required.")).map {
      FileRead(requiredField(it, "path"), integer(it, "offset"), integer(it, "limit"), integer(it, "byte_offset") ?: 0)
    })
    "create_directory" -> Operation.CreateDirectory(workspace, required("path"), flag("parents"), key)
    "search" -> Operation.Search(workspace, required("query"), text("path"), flag("regex"), flag("case_sensitive"),
      text("mode") ?: "all", strings("include_globs"), strings("exclude_globs"),
      number("context_before") ?: 0, number("context_after") ?: 0, number("limit") ?: 200)
    "git_status" -> Operation.GitStatus(workspace)
    "git_diff" -> Operation.GitDiff(workspace, flag("staged"), strings("paths"), text("mode") ?: "patch")
    "git_log" -> Operation.GitLog(workspace, number("limit"))
    "write_file" -> Operation.WriteFile(workspace, required("path"), required("content"), key)
    "edit_file" -> Operation.EditFile(workspace, required("path"), text("old_text"), text("new_text"), key,
      objects("edits")?.map { TextEdit(requiredField(it, "old_text"), requiredField(it, "new_text")) }, flag("preview"))
    RUN_COMMAND -> Operation.RunCommand(workspace, required("command"), text("cwd"), key)
    GET_RESULT -> Operation.GetResult(workspace, required("handle"))
    else -> throw IllegalArgumentException("'$tool' is in the catalog but this frontend cannot build it.")
  }
}
