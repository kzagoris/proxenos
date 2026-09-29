package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.core.Activity
import io.github.kzagoris.proxenos.core.WorkspaceOperationsPipeline
import io.github.kzagoris.proxenos.core.WorkspaceRegistry
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ArgumentType
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

/**
 * Decoding, with no transport in the way. Its subject is the one place the adapter and the
 * core have to agree on a spelling: the catalog declares an argument's name and this is what
 * reads it back out of a call, so a rename on either side routes a value into a default and
 * nothing anywhere looks wrong.
 */
class ToolCallsTest {
  @TempDir
  lateinit var temporary: Path

  /**
   * Every argument the catalog declares, built from **the catalog's own names** and expected
   * back on the Operation. A name misspelled on either side leaves that argument at its
   * default here, and each expectation below is a value no default could produce.
   */
  @Test
  fun `each entry's declared arguments arrive on its Operation`() {
    assertEquals(
      listOf(
        Operation.ListWorkspaces,
        Operation.ListDirectory("api", "src/Main.kt"),
        Operation.ReadFile("api", "src/Main.kt", 12, 30),
        Operation.Search("api", "needle", "src/Main.kt", regex = true, caseSensitive = true),
        Operation.GitStatus("api"),
        Operation.GitDiff("api", staged = true),
        Operation.GitLog("api", 30),
        Operation.WriteFile("api", "src/Main.kt", "the new contents", "r-1"),
        Operation.EditFile("api", "src/Main.kt", "before", "after", "r-1"),
        Operation.RunCommand("api", "make build", "src", "r-1"),
        Operation.GetResult("api", "h-1"),
      ),
      catalog().map { spec -> assertIs<ToolCall.Ready>(decode(spec, fullyPopulated(spec))).operation },
    )
  }

  @Test
  fun `a tool name no Operation is bound to is answered rather than decoded`() {
    // The catalog is flat and static (§3), and an entry is published whether or not its
    // Operation has landed — so a call to one is answered rather than quietly dropped.
    val unbound = OperationSpec("some_later_tool", "Not in this build.", AccessLevel.Read, emptyList())
    assertEquals(ToolCall.Unbound("some_later_tool"), decode(unbound, fullyPopulated(unbound)))
  }

  /**
   * The two the core has something to say about, handed on the way it expects them: a Workspace
   * that did not arrive is null, so the failure names the exposed Workspaces (§3), and a
   * Delivery key that did not arrive is blank, so the failure says a mutation needs one (§6.4).
   */
  @Test
  fun `an absent workspace and an absent request_id are the core's to answer`() {
    val write = assertIs<ToolCall.Ready>(
      decode(entry("write_file"), buildJsonObject { put("path", "a.txt"); put("content", "x") }),
    ).operation as Operation.WriteFile
    assertEquals(null, write.workspace)
    assertEquals("", write.deliveryKey)
  }

  @Test
  fun `a required argument the core cannot speak for leaves no Operation to have an outcome`() {
    val missing = assertIs<ToolCall.Malformed>(
      decode(entry("write_file"), buildJsonObject { put("workspace", "api"); put("request_id", "r-1") }),
    )
    assertContains(missing.complaint, "'path'")
    assertContains(missing.complaint, NOTHING_ATTEMPTED)
  }

  /** JSON `null` is the argument not arriving, not a value to carry into the Operation. */
  @Test
  fun `a null argument reads as one that did not arrive`() {
    val read = assertIs<ToolCall.Ready>(
      decode(
        entry("read_file"),
        buildJsonObject { put("workspace", "api"); put("path", "a.txt"); put("offset", JsonNull) },
      ),
    ).operation
    assertEquals(Operation.ReadFile("api", "a.txt", null, null), read)
  }

  /**
   * A number or a flag that arrived quoted is taken at its word. The schema already says what
   * it has to be, models routinely stringify one, and refusing `"20"` buys a round trip and no
   * safety — but a value that is not the type at all is still refused rather than guessed at.
   */
  @Test
  fun `a quoted number and a quoted flag are read, and a word that is neither is refused`() {
    val quoted = assertIs<ToolCall.Ready>(
      decode(
        entry("search"),
        buildJsonObject {
          put("workspace", "api"); put("query", "needle"); put("regex", "true"); put("case_sensitive", "false")
        },
      ),
    ).operation
    assertEquals(Operation.Search("api", "needle", null, regex = true, caseSensitive = false), quoted)

    val nonsense = assertIs<ToolCall.Malformed>(
      decode(entry("git_log"), buildJsonObject { put("workspace", "api"); put("limit", "a few") }),
    )
    assertContains(nonsense.complaint, "'limit'")
    assertContains(nonsense.complaint, "whole number")
  }

  @Test
  fun `an argument that arrived as a structure is refused, not flattened`() {
    val structured = assertIs<ToolCall.Malformed>(
      decode(entry("read_file"), buildJsonObject { put("workspace", "api"); put("path", buildJsonObject { }) }),
    )
    assertContains(structured.complaint, "'path'")
  }

  /** A sample for every argument the entry declares, keyed by the name the entry declares. */
  private fun fullyPopulated(spec: OperationSpec): JsonObject = buildJsonObject {
    spec.arguments.forEach { argument ->
      put(
        argument.name,
        when (argument.type) {
          ArgumentType.Flag -> JsonPrimitive(true)
          ArgumentType.Integer -> JsonPrimitive(sampleNumbers.getValue(argument.name))
          ArgumentType.Text -> JsonPrimitive(sampleText.getValue(argument.name))
        },
      )
    }
  }

  private fun entry(name: String): OperationSpec = catalog().single { it.name == name }

  private fun catalog(): List<OperationSpec> = WorkspaceOperationsPipeline(
    WorkspaceRegistry(temporary.resolve("registry.properties")),
    Activity(temporary.resolve("activity")),
  ).catalog

  private companion object {
    /** Values chosen so no default could produce them, keyed by the catalog's own names. */
    val sampleText = mapOf(
      "workspace" to "api",
      "path" to "src/Main.kt",
      "content" to "the new contents",
      "old_text" to "before",
      "new_text" to "after",
      "query" to "needle",
      "command" to "make build",
      "cwd" to "src",
      "handle" to "h-1",
      "request_id" to "r-1",
    )
    val sampleNumbers = mapOf("offset" to 12, "limit" to 30)
  }
}
