package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.github.kzagoris.proxenos.coreapi.Outcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a `tools/call` turned out to be. The adapter builds an Operation or it does not; it
 * never half-builds one, and it never decides an Operation's outcome — that is the core's,
 * down the one pipeline.
 */
internal sealed interface ToolCall {
  data class Ready(val operation: Operation<*>) : ToolCall

  /** No Operation can be built from these arguments, so none was attempted. */
  data class Malformed(val complaint: String) : ToolCall

  /**
   * A catalog entry whose Operation has not landed in this build. The catalog is flat and
   * static, so an entry is published all the same, and a call to it is answered rather
   * than quietly dropped. All eleven are bound now; this stays because the catalog is data and
   * an entry added to it is not obliged to arrive with its Operation on the same day.
   */
  data class Unbound(val tool: String) : ToolCall
}

/**
 * The call, as an Operation. Two arguments are read the way the core expects them to arrive
 * rather than refused here, because the core has something to say about each and the adapter
 * does not: an absent `workspace` is answered by naming the Workspaces that are exposed,
 * and an absent `request_id` by the failure that says a mutation needs one.
 * Every other missing or mistyped argument leaves no Operation to have an outcome at all.
 */
internal fun decode(spec: OperationSpec, arguments: JsonObject?): ToolCall = try {
  val read = Arguments(spec.name, arguments)
  val operation: Operation<*> = when (spec.name) {
    "list_workspaces" -> Operation.ListWorkspaces
    "list_directory" -> Operation.ListDirectory(read.workspace(), read.text("path"))
    "read_file" -> Operation.ReadFile(
      read.workspace(), read.requiredText("path"), read.number("offset"), read.number("limit"),
    )
    "search" -> Operation.Search(
      read.workspace(), read.requiredText("query"), read.text("path"),
      read.flag("regex"), read.flag("case_sensitive"),
    )
    "git_status" -> Operation.GitStatus(read.workspace())
    "git_diff" -> Operation.GitDiff(read.workspace(), read.flag("staged"))
    "git_log" -> Operation.GitLog(read.workspace(), read.number("limit"))
    "write_file" -> Operation.WriteFile(
      read.workspace(), read.requiredText("path"), read.requiredText("content"), read.key(),
    )
    "edit_file" -> Operation.EditFile(
      read.workspace(), read.requiredText("path"), read.requiredText("old_text"),
      read.requiredText("new_text"), read.key(),
    )
    "run_command" -> Operation.RunCommand(
      read.workspace(), read.requiredText("command"), read.text("cwd"), read.key(),
    )
    "get_result" -> Operation.GetResult(read.workspace(), read.requiredText("handle"))
    else -> return ToolCall.Unbound(spec.name)
  }
  ToolCall.Ready(operation)
} catch (malformed: MalformedCall) {
  ToolCall.Malformed(malformed.complaint)
}

/**
 * The promise a refusal from this adapter carries, written once. It is the same guarantee a
 * `failed` Outcome makes and it has to be as strong, because a call the adapter would not
 * build is a call that never reached the disk at all — and a model that cannot tell that from
 * an [Outcome.Uncertain] is the hazard ADR 0001 exists for.
 */
internal const val NOTHING_ATTEMPTED: String = "Nothing was attempted and nothing changed."

/** Thrown while reading one argument, caught where the call is decoded and nowhere else. */
private class MalformedCall(val complaint: String) : RuntimeException(complaint)

/**
 * One call's arguments. An argument that is absent and one that arrived as JSON `null` are the
 * same thing here — the argument did not arrive — because nothing downstream can act on the
 * difference, and a model that sent `null` for an optional argument meant to leave it out.
 */
private class Arguments(private val tool: String, private val arguments: JsonObject?) {
  fun workspace(): String? = text(Operation.WORKSPACE_ARGUMENT)

  /** Blank is how the pipeline reads a Delivery key that did not arrive. */
  fun key(): String = text(Operation.KEY_ARGUMENT) ?: ""

  fun text(name: String): String? = primitive(name)?.let {
    if (it.isString) it.content else throw MalformedCall(mistyped(name, "text", it))
  }

  fun requiredText(name: String): String = text(name) ?: throw MalformedCall(missing(name))

  /**
   * Lenient about a number that arrived quoted: models routinely stringify one, the schema
   * already says what it has to be, and refusing `"20"` buys a round trip and no safety. What
   * is not a whole number at all is still refused, since the core's own `limit` complaints are
   * about the value rather than about the type.
   */
  fun number(name: String): Int? = primitive(name)?.let {
    it.content.toIntOrNull() ?: throw MalformedCall(mistyped(name, "a whole number", it))
  }

  /** Absent is false: every flag in the catalog is opt-in, and none of them defaults to on. */
  fun flag(name: String): Boolean = primitive(name)?.let {
    it.content.toBooleanStrictOrNull() ?: throw MalformedCall(mistyped(name, "true or false", it))
  } ?: false

  private fun primitive(name: String): JsonPrimitive? = when (val value = arguments?.get(name)) {
    null, JsonNull -> null
    is JsonPrimitive -> value
    else -> throw MalformedCall(mistyped(name, "a single value", value))
  }

  private fun missing(name: String): String =
    "'$tool' was called without its required '$name' argument. $NOTHING_ATTEMPTED " +
      "Supply '$name' and call again."

  private fun mistyped(name: String, expected: String, got: JsonElement): String =
    "'$tool' needs '$name' to be $expected, and it arrived as $got. $NOTHING_ATTEMPTED"
}
