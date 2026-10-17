package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.core.*
import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class TryOperationsTest {
  @TempDir lateinit var temporary: Path

  @Test fun `Try preview preserves the file while batch apply uses the current version`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val file = Files.writeString(root.resolve("notes"), "alpha\nbeta\n")
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val ops = WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity"))).operationsFor(Origin.Frontend)
    val preview = operationFrom("gui", "edit_file", "api", mapOf("path" to "notes", "old_text" to "alpha", "new_text" to "first", "preview" to "yes"))
    val result = ops.perform(preview)
    assertTrue((assertIs<Outcome.Ok<*>>(result).value as FileWritten).preview)
    assertEquals("alpha\nbeta\n", Files.readString(file))
    val batch = operationFrom("tui", "edit_file", "api", mapOf("path" to "notes", "edits" to """[{"old_text":"alpha","new_text":"first"},{"old_text":"beta","new_text":"second"}]"""))
    assertIs<Outcome.Ok<*>>(ops.perform(batch))
    assertEquals("first\nsecond\n", Files.readString(file))
  }

  @Test fun `Try carries arrays and nondefault selections instead of ignoring them`() {
    val read = assertIs<Operation.ReadFiles>(operationFrom("gui", "read_files", "api", mapOf("files" to """[{"path":"a","offset":2,"limit":3,"byte_offset":4}]""")))
    assertEquals(listOf(FileRead("a", 2, 3, 4)), read.files)
    val search = assertIs<Operation.Search>(operationFrom("gui", "search", "api", mapOf("query" to "x", "mode" to "content", "include_globs" to """["**/*.kt"]""", "exclude_globs" to """["test/**"]""", "context_before" to "2", "context_after" to "3", "limit" to "4")))
    assertEquals(Operation.Search("api", "x", mode = "content", includeGlobs = listOf("**/*.kt"), excludeGlobs = listOf("test/**"), contextBefore = 2, contextAfter = 3, limit = 4), search)
    assertEquals(Operation.ListDirectory("api", "src", "a", 5), operationFrom("gui", "list_directory", "api", mapOf("path" to "src", "after" to "a", "limit" to "5")))
    assertEquals(Operation.GitDiff("api", true, listOf("a*"), "stat"), operationFrom("tui", "git_diff", "api", mapOf("staged" to "yes", "paths" to """["a*"]""", "mode" to "stat")))
    val directory = assertIs<Operation.CreateDirectory>(operationFrom("gui", "create_directory", "api", mapOf("path" to "a/b", "parents" to "yes")))
    assertTrue(directory.parents)
    assertTrue(directory.deliveryKey.startsWith("gui-"))
    assertEquals(10, assertIs<Operation.ReadFile>(operationFrom("gui", "read_file", "api", mapOf("path" to "a", "byte_offset" to "10"))).byteOffset)
  }

  @Test fun `Try refuses malformed batch values before an Operation can be sent`() {
    for (files in listOf("not JSON", "[1]", "[{}]", """[{"path":7}]""", """[{"path":"a","offset":"two"}]""")) {
      assertFailsWith<IllegalArgumentException> { operationFrom("gui", "read_files", "api", mapOf("files" to files)) }
    }
    assertFailsWith<IllegalArgumentException> { operationFrom("tui", "search", "api", mapOf("query" to "x", "include_globs" to "[1]")) }
  }
}
