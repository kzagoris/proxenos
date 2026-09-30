package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.coreapi.ArgumentSpec
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import java.util.UUID

/**
 * What a TryOperation asks for: everything but the Workspace, which is the one it is tried
 * against, and the `request_id`, which is minted.
 */
fun askedOf(spec: OperationSpec): List<ArgumentSpec> =
  spec.arguments.filter { it.name != Operation.WORKSPACE_ARGUMENT && it.name != Operation.KEY_ARGUMENT }

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
  val key = "$caller-${UUID.randomUUID()}"
  return when (tool) {
    "list_workspaces" -> Operation.ListWorkspaces
    "list_directory" -> Operation.ListDirectory(workspace, text("path"))
    "read_file" -> Operation.ReadFile(workspace, required("path"), number("offset"), number("limit"))
    "search" -> Operation.Search(workspace, required("query"), text("path"), flag("regex"), flag("case_sensitive"))
    "git_status" -> Operation.GitStatus(workspace)
    "git_diff" -> Operation.GitDiff(workspace, flag("staged"))
    "git_log" -> Operation.GitLog(workspace, number("limit"))
    "write_file" -> Operation.WriteFile(workspace, required("path"), required("content"), key)
    "edit_file" -> Operation.EditFile(workspace, required("path"), required("old_text"), required("new_text"), key)
    RUN_COMMAND -> Operation.RunCommand(workspace, required("command"), text("cwd"), key)
    GET_RESULT -> Operation.GetResult(workspace, required("handle"))
    else -> throw IllegalArgumentException("'$tool' is in the catalog but this frontend cannot build it.")
  }
}
