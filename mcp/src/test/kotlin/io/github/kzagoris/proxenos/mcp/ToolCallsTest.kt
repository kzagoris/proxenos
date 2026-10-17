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
  fun `extended arguments arrive on their Operations without falling back to defaults`() {
    fun operation(tool: String, json: String) = assertIs<ToolCall.Ready>(decode(entry(tool),
      kotlinx.serialization.json.Json.parseToJsonElement(json).let { it as JsonObject })).operation
    assertEquals(Operation.ReadFiles("api", listOf(io.github.kzagoris.proxenos.coreapi.FileRead("a", 2, 3, 4))),
      operation("read_files", """{"workspace":"api","files":[{"path":"a","offset":2,"limit":3,"byte_offset":4}]}"""))
    assertEquals(Operation.Search("api", "x", mode = "content", includeGlobs = listOf("**/*.kt"), excludeGlobs = listOf("test/**"), contextBefore = 2, contextAfter = 3, limit = 4),
      operation("search", """{"workspace":"api","query":"x","mode":"content","include_globs":["**/*.kt"],"exclude_globs":["test/**"],"context_before":2,"context_after":3,"limit":4}"""))
    assertEquals(Operation.ListDirectory("api", "src", "a", 20), operation("list_directory", """{"workspace":"api","path":"src","after":"a","limit":20}"""))
    assertEquals(Operation.GitDiff("api", true, listOf("a*"), "stat"), operation("git_diff", """{"workspace":"api","staged":true,"paths":["a*"],"mode":"stat"}"""))
    assertEquals(Operation.CreateDirectory("api", "a/b", true, "key"), operation("create_directory", """{"workspace":"api","path":"a/b","parents":true,"request_id":"key"}"""))
    assertEquals(Operation.EditFile("api", "a", deliveryKey = "key", edits = listOf(io.github.kzagoris.proxenos.coreapi.TextEdit("old", "new")), preview = true),
      operation("edit_file", """{"workspace":"api","path":"a","edits":[{"old_text":"old","new_text":"new"}],"preview":true,"request_id":"key"}"""))
  }

  @Test
  fun `a tool name no Operation is bound to is answered rather than decoded`() {
    // The catalog is flat and static, and an entry is published whether or not its
    // Operation has landed — so a call to one is answered rather than quietly dropped.
    val unbound = OperationSpec("some_later_tool", "Not in this build.", AccessLevel.Read, emptyList())
    assertEquals(ToolCall.Unbound("some_later_tool"), decode(unbound, buildJsonObject { }))
  }

  /**
   * The two the core has something to say about, handed on the way it expects them: a Workspace
   * that did not arrive is null, so the failure names the exposed Workspaces, and a
   * Delivery key that did not arrive is blank, so the failure says a mutation needs one.
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

  private fun entry(name: String): OperationSpec = catalog().single { it.name == name }

  private fun catalog(): List<OperationSpec> = WorkspaceOperationsPipeline(
    WorkspaceRegistry(temporary.resolve("registry.properties")),
    Activity(temporary.resolve("activity")),
  ).catalog

}
