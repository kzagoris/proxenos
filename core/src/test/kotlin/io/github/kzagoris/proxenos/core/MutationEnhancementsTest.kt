package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class MutationEnhancementsTest {
  @TempDir
  lateinit var temporary: Path

  private suspend fun operations(root: Path): WorkspaceOperations {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    return WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity")))
      .operationsFor(Origin.ChatGpt)
  }

  private fun directory(path: String, parents: Boolean = false, key: String = UUID.randomUUID().toString()) =
    Operation.CreateDirectory("api", path, parents, key)

  private fun batch(path: String, vararg edits: TextEdit, preview: Boolean = false) =
    Operation.EditFile("api", path, deliveryKey = UUID.randomUUID().toString(), edits = edits.toList(), preview = preview)

  @Test
  fun `directory parents are explicit and repeated Delivery preserves the created components`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val operations = operations(root)
    assertIs<Outcome.Failed>(operations.perform(directory("one/two")))
    assertFalse(Files.exists(root.resolve("one")))

    val op = directory("one/two", parents = true)
    val made = assertIs<Outcome.Ok<DirectoryCreated>>(operations.perform(op)).value
    assertEquals(DirectoryCreated("one/two", listOf("one", "one/two"), false), made)
    assertTrue(Files.isDirectory(root.resolve("one/two")))
    val repeated = assertIs<Outcome.Ok<DirectoryCreated>>(operations.perform(op))
    assertTrue(repeated.recorded)
    assertEquals(made, repeated.value)
    assertEquals(DirectoryCreated("one/two", emptyList(), true),
      assertIs<Outcome.Ok<DirectoryCreated>>(operations.perform(directory("one/two"))).value)
  }

  @Test
  fun `directory creation refuses files and symlinks escaping the Root`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val operations = operations(root)
    val outside = Files.createDirectory(temporary.resolve("outside"))
    Files.createSymbolicLink(root.resolve("link"), outside)
    Files.writeString(root.resolve("file"), "untouched")
    assertIs<Outcome.Failed>(operations.perform(directory("file", parents = true)))
    assertIs<Outcome.Failed>(operations.perform(directory("link/new", parents = true)))
    assertFalse(Files.exists(outside.resolve("new")))
    assertEquals("untouched", Files.readString(root.resolve("file")))
  }

  @Test
  fun `directory failure after creating a parent is Uncertain and leaves the known parent`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val operations = operations(root)
    val outcome = assertIs<Outcome.Uncertain>(operations.perform(directory("made/${"x".repeat(256)}", parents = true)))
    assertEquals(listOf("made"), outcome.createdPaths)
    assertFalse(outcome.reason is Uncertainty.RootBrokeMidOperation, "ordinary creation failure did not break the Root")
    assertContains(outcome.message, Outcome.Uncertain.EFFECTS_UNCERTAIN)
    assertTrue(Files.isDirectory(root.resolve("made")))
  }

  @Test
  fun `creation whose full effects cannot fit the reply is refused before making parents`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val operations = operations(root)
    val path = List(200) { "abcdefghij" }.joinToString("/") + "/" + "x".repeat(256)
    assertIs<Outcome.Failed>(operations.perform(directory(path, parents = true)))
    assertFalse(Files.exists(root.resolve("abcdefghij")))
  }

  @Test
  fun `concurrent directory creation reports each component only for its creator`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val operations = operations(root)
    val outcomes = coroutineScope {
      (1..12).map { async { operations.perform(directory("one/two", parents = true)) } }.awaitAll()
    }
    val created = outcomes.flatMap { assertIs<Outcome.Ok<DirectoryCreated>>(it).value.createdPaths }
    assertEquals(listOf("one", "one/two"), created.sorted())
    assertTrue(Files.isDirectory(root.resolve("one/two")))
  }

  @Test
  fun `batch edits match the original and preserve CRLF and mode bits`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val file = Files.writeString(root.resolve("notes"), "alpha\r\nbeta\r\ngamma\r\n")
    val mode = PosixFilePermissions.fromString("rwxr-x---")
    Files.setPosixFilePermissions(file, mode)
    val operations = operations(root)
    val result = assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes",
      TextEdit("beta", "alpha"), TextEdit("alpha\r\n", "first\nsecond\n")))).value
    assertEquals("first\r\nsecond\r\nalpha\r\ngamma\r\n", Files.readString(file))
    assertEquals(mode, Files.getPosixFilePermissions(file))
    assertContains(result.diff, "-alpha")
    assertContains(result.diff, "+first")
  }

  @Test
  fun `batch conflicts identify absent ambiguous and overlapping edits without changing bytes`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val file = Files.writeString(root.resolve("notes"), "alpha beta alpha")
    val operations = operations(root)
    val unmatched = assertIs<Outcome.Failed>(operations.perform(batch("notes",
      TextEdit("missing", "x"), TextEdit("alpha", "x"))))
    assertEquals(listOf(EditConflict(0, 0), EditConflict(1, 2)), unmatched.conflicts)
    val overlapping = assertIs<Outcome.Failed>(operations.perform(batch("notes",
      TextEdit("alpha beta", "x"), TextEdit("beta alpha", "y"))))
    assertTrue(overlapping.conflicts.any { it.index == 1 && it.overlapsWith == 0 })
    assertEquals("alpha beta alpha", Files.readString(file))
    assertIs<Outcome.Failed>(operations.perform(batch("notes", TextEdit("beta", "new"), TextEdit("new", "later"))))
    assertEquals("alpha beta alpha", Files.readString(file))
  }

  @Test
  fun `preview does not replace the file and apply reads its current version`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val file = Files.writeString(root.resolve("notes"), "alpha\nbeta\n")
    val hardlink = Files.createLink(root.resolve("hard"), file)
    val operations = operations(root)
    val preview = assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes", TextEdit("beta", "delta"), preview = true))).value
    assertTrue(preview.preview)
    assertEquals(12, preview.bytes)
    assertContains(preview.diff, "-beta")
    assertContains(preview.diff, "+delta")
    assertTrue(Files.isSameFile(file, hardlink))
    assertEquals("alpha\nbeta\n", Files.readString(file))
    Files.writeString(file, "changed\n")
    assertIs<Outcome.Failed>(operations.perform(batch("notes", TextEdit("beta", "delta"))))
    assertEquals("changed\n", Files.readString(file))
  }

  @Test
  fun `edit forms sizes and empty old text are validated before changing bytes`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val file = Files.writeString(root.resolve("notes"), "alpha")
    val operations = operations(root)
    val invalid = listOf(
      Operation.EditFile("api", "notes", deliveryKey = "absent"),
      Operation.EditFile("api", "notes", "alpha", deliveryKey = "partial"),
      Operation.EditFile("api", "notes", "alpha", "x", "both", listOf(TextEdit("alpha", "x"))),
      batch("notes"),
      batch("notes", TextEdit("", "x")),
      Operation.EditFile("api", "notes", deliveryKey = "too-many", edits = List(101) { TextEdit("alpha", "x") }),
    )
    for (op in invalid) assertIs<Outcome.Failed>(operations.perform(op))
    assertEquals("alpha", Files.readString(file))
  }

  @Test
  fun `large edit diffs are bounded without shortening the landed replacement`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val file = Files.writeString(root.resolve("notes"), "original\n")
    val operations = operations(root)
    val replacement = "界".repeat(12_000) + "\n"
    val result = assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes", TextEdit("original\n", replacement)))).value
    assertTrue(result.diffTruncated)
    assertTrue(result.diff.toByteArray().size <= Operation.OUTPUT_CAP_BYTES)
    assertEquals(replacement, Files.readString(file))
  }

  @Test
  fun `diffs describe merged lines and files without a trailing newline`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    Files.writeString(root.resolve("notes"), "alpha\nbeta\ngamma")
    val operations = operations(root)
    val result = assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes", TextEdit("alpha\n", "first")))).value
    assertContains(result.diff, "+firstbeta\n")
    assertEquals("firstbeta\ngamma", Files.readString(root.resolve("notes")))

    val last = assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes", TextEdit("gamma", "last")))).value
    assertContains(last.diff, "\\ No newline at end of file")
    assertContains(last.diff, "+last\n")
  }

  @Test
  fun `a preview diff reconstructs separated changes with inserted and removed newlines`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val original = "alpha\nbeta\n" + (1..12).joinToString("\n", postfix = "\n") { "line $it" } + "omega"
    val file = Files.writeString(root.resolve("notes"), original)
    val operations = operations(root)
    val edits = arrayOf(TextEdit("alpha\n", "first"), TextEdit("omega", "last\nextra"))
    val preview = assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes", *edits, preview = true))).value
    val process = ProcessBuilder("git", "apply", "--unidiff-zero", "-")
      .directory(root.toFile()).redirectErrorStream(true).start()
    process.outputStream.bufferedWriter().use { it.write(preview.diff) }
    val detail = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), detail)
    val fromDiff = Files.readString(file)
    Files.writeString(file, original)
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(batch("notes", *edits)))
    assertEquals(Files.readString(file), fromDiff)
  }
}
