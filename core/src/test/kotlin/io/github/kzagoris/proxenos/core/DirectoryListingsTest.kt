package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DirectoryListingsTest {
  @TempDir
  lateinit var temporary: Path

  private suspend fun workspace(): Pair<Path, WorkspaceOperations> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    return root to WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("activity")))
      .operationsFor(Origin.ChatGpt)
  }

  private suspend fun page(operations: WorkspaceOperations, after: String? = null, limit: Int = 1000) =
    assertIs<Outcome.Ok<DirectoryListing>>(
      operations.perform(Operation.ListDirectory("api", after = after, limit = limit)),
    ).value

  @Test
  fun `a directory past the byte bound can be read in consecutive pages without gaps`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    val names = (0..1999).map { number -> "${number.toString().padStart(4, '0')}-" + "\"\\\n".repeat(35) }
    names.forEach { Files.createFile(root.resolve(it)) }
    val seen = mutableListOf<String>()
    val json = Json { encodeDefaults = true }
    var after: String? = null
    do {
      val listing = page(operations, after)
      assertEquals(2000, listing.totalEntries)
      assertTrue(json.encodeToString(listing).toByteArray().size <= Operation.OUTPUT_CAP_BYTES)
      assertTrue(listing.entries.isNotEmpty(), "a continuation must advance")
      if (listing.incomplete) {
        assertTrue(listing.cappedByBytes)
        assertEquals(listing.entries.last().name, listing.nextAfter)
      } else {
        assertNull(listing.nextAfter)
      }
      seen += listing.entries.map { it.name }
      after = listing.nextAfter
    } while (after != null)
    assertEquals(names, seen)
  }

  @Test
  fun `a count cursor remains exclusive when its entry disappears and the directory changes`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    listOf("b", "d", "f").forEach { Files.createFile(root.resolve(it)) }
    val first = page(operations, limit = 2)
    assertEquals(listOf("b", "d"), first.entries.map { it.name })
    assertTrue(first.incomplete)
    assertFalse(first.cappedByBytes)
    assertEquals("d", first.nextAfter)
    assertEquals(3, first.totalEntries)

    Files.delete(root.resolve("d"))
    listOf("a", "c", "e", "z").forEach { Files.createFile(root.resolve(it)) }
    val second = page(operations, first.nextAfter, limit = 2)
    assertEquals(listOf("e", "f"), second.entries.map { it.name })
    assertEquals(6, second.totalEntries, "each page counts the current directory")
    assertEquals("f", second.nextAfter)
    val last = page(operations, second.nextAfter, limit = 2)
    assertEquals(listOf("z"), last.entries.map { it.name })
    assertFalse(last.incomplete)
    assertNull(last.nextAfter)
    val beyond = page(operations, after = "zz", limit = 2)
    assertTrue(beyond.entries.isEmpty())
    assertFalse(beyond.incomplete)
    assertEquals(6, beyond.totalEntries)
  }

  @Test
  fun `directory pages refuse invalid counts and path-shaped cursors`() = runBlocking<Unit> {
    val (_, operations) = workspace()
    for (limit in listOf(0, 1001)) {
      val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ListDirectory("api", limit = limit)))
      assertEquals(Failure.InvalidArgument("limit"), refused.reason)
    }
    for (after in listOf("sub/name", "bad\u0000name")) {
      val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ListDirectory("api", after = after)))
      assertEquals(Failure.InvalidArgument("after"), refused.reason)
    }
  }

  @Test
  fun `an empty directory whose escaped path exhausts the reply budget is refused`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    val path = List(10) { "\u0001".repeat(240) }.joinToString("/")
    Files.createDirectories(root.resolve(path))
    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ListDirectory("api", path)))
    assertEquals(Failure.InvalidArgument("path"), refused.reason)
  }
}
