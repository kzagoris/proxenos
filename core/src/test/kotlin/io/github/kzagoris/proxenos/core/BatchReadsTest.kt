package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class BatchReadsTest {
  @TempDir lateinit var temporary: Path

  @Test fun `batch keeps independent path failures and duplicates in one Activity entry`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    Files.writeString(root.resolve("a"), "one\ntwo\nthree\n")
    Files.write(root.resolve("binary"), byteArrayOf(0))
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val activity = Activity(temporary.resolve("activity"))
    val ops = WorkspaceOperationsPipeline(registry, activity).operationsFor(Origin.ChatGpt)
    val result = assertIs<Outcome.Ok<BatchRead>>(ops.perform(Operation.ReadFiles("api", listOf(
      FileRead("a", 2, 1), FileRead("../escape"), FileRead("binary"), FileRead("missing"),
      FileRead("a", 0), FileRead("a", 1, 1),
    )))).value
    assertEquals(listOf("ok", "failed", "failed", "failed", "failed", "ok"), result.items.map { it.status })
    assertEquals("two\n", result.items[0].content?.text)
    assertEquals("one\n", result.items[5].content?.text)
    assertEquals(1, activity.entries().size)
  }

  @Test fun `long unicode lines resume without skipping bytes or repeating a cursor`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val expected = "🙂\"\u0001".repeat(3000) + "\nsecond\n"
    Files.writeString(root.resolve("a"), expected)
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val ops = WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity"))).operationsFor(Origin.ChatGpt)
    val reconstructed = StringBuilder()
    var offset = 1
    var bytes = 0
    val cursors = mutableSetOf<Pair<Int, Int>>()
    do {
      assertTrue(cursors.add(offset to bytes), "a cursor repeated without progress")
      val page = assertIs<Outcome.Ok<BatchRead>>(ops.perform(Operation.ReadFiles("api", listOf(FileRead("a", offset, byteOffset = bytes))))).value
      val content = assertNotNull(page.items.single().content)
      assertEquals(2, content.totalLines)
      reconstructed.append(content.text)
      offset = content.nextOffset ?: break
      bytes = content.nextByteOffset ?: 0
    } while (cursors.size < 100)
    assertEquals(expected, reconstructed.toString())
  }

  @Test fun `byte continuation also progresses through malformed UTF8`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    Files.write(root.resolve("a"), ByteArray(10000) { 0x80.toByte() })
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val ops = WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity"))).operationsFor(Origin.ChatGpt)
    val first = assertIs<Outcome.Ok<FileContent>>(ops.perform(Operation.ReadFile("api", "a"))).value
    assertTrue(assertNotNull(first.nextByteOffset) > 0)
    val second = assertIs<Outcome.Ok<FileContent>>(ops.perform(Operation.ReadFile("api", "a", first.nextOffset, byteOffset = first.nextByteOffset!!))).value
    assertTrue(second.nextByteOffset == null || second.nextByteOffset!! > first.nextByteOffset!!)
    assertEquals(10000, first.text.length + second.text.length)
  }

  @Test fun `budget exhaustion preserves every item status and Workspace refusal is whole`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    repeat(20) { Files.writeString(root.resolve("a$it"), "\u0001".repeat(10000)) }
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    val ops = WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity"))).operationsFor(Origin.ChatGpt)
    val files = (0..19).map { FileRead("a$it") }
    val answer = assertIs<Outcome.Ok<BatchRead>>(ops.perform(Operation.ReadFiles("api", files))).value
    assertEquals(20, answer.items.size)
    assertTrue(answer.incomplete)
    assertTrue(answer.items.any { it.status == "ok" })
    assertTrue(answer.items.any { it.status == "not_processed" })
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
    assertIs<Failure.NoSuchWorkspace>(assertIs<Outcome.Failed>(ops.perform(Operation.ReadFiles("api", files))).reason)
  }
  @Test fun `a byte cursor beyond its line is refused even when a later line is cut`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    Files.writeString(root.resolve("a"), "short\n" + "long".repeat(30000))
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val ops = WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity"))).operationsFor(Origin.ChatGpt)
    assertIs<Outcome.Failed>(ops.perform(Operation.ReadFile("api", "a", byteOffset = 100)))
  }

}
