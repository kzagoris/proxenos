package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `list_directory` and `search`. The decision under test is how search enumerates:
 * inside a repository it asks Git for tracked-plus-untracked-not-ignored, which is the
 * project's own ignore rules rather than a second, subtly different `.gitignore` parser.
 */
class FileSearchTest {
  @TempDir
  lateinit var temporary: Path

  /** A readable Workspace named [name] over a fresh directory, and the pipeline reaching it. */
  private suspend fun workspace(
    name: String = "api",
    budget: Duration = Operation.SEARCH_BUDGET,
  ): Pair<Path, WorkspaceOperations> {
    val registry = WorkspaceRegistry(temporary.resolve("$name-registry.properties"))
    val activity = Activity(temporary.resolve("$name-activity"))
    val root = Files.createDirectory(temporary.resolve(name))
    registry.perform(ManagementAct.Register(root.toString(), name))
    return root to WorkspaceOperationsPipeline(registry, activity, budget).operationsFor(Origin.ChatGpt)
  }

  private suspend fun searched(operations: WorkspaceOperations, op: Operation.Search): SearchResults =
    assertIs<Outcome.Ok<SearchResults>>(operations.perform(op)).value

  private fun SearchResults.paths(): Set<String> = hits.map { it.path }.toSet()

  private fun git(root: Path, vararg arguments: String) {
    val process = ProcessBuilder(listOf("git", *arguments)).directory(root.toFile())
      .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    assertTrue(process.waitFor(30, SECONDS))
    assertEquals(0, process.exitValue(), "git ${arguments.joinToString(" ")} failed")
  }

  private fun gitInstalled(): Boolean = try {
    ProcessBuilder("git", "--version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
  } catch (_: IOException) {
    false
  }

  @Test
  fun `list_directory lists a directory's entries, and defaults to the Root itself`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.writeString(root.resolve("notes.md"), "one\n")
    Files.createDirectory(root.resolve("src"))
    Files.writeString(root.resolve("src/main.kt"), "fun main() {}\n")
    Files.createSymbolicLink(root.resolve("link"), root.resolve("notes.md"))

    val listing = assertIs<Outcome.Ok<DirectoryListing>>(operations.perform(Operation.ListDirectory("api"))).value
    assertEquals(listOf("link", "notes.md", "src"), listing.entries.map { it.name })
    assertEquals(
      listOf(EntryKind.Symlink, EntryKind.File, EntryKind.Directory),
      listing.entries.map { it.kind },
    )
    assertEquals(4L, listing.entries.single { it.name == "notes.md" }.sizeBytes)
    assertNull(listing.entries.single { it.name == "src" }.sizeBytes)
    assertEquals(3, listing.totalEntries)
    assertFalse(listing.cappedByBytes)

    val sub = assertIs<Outcome.Ok<DirectoryListing>>(operations.perform(Operation.ListDirectory("api", "src"))).value
    assertEquals(listOf("main.kt"), sub.entries.map { it.name })
    assertEquals("src", sub.path)
  }

  @Test
  fun `list_directory is confined to the Root, and a file is not a directory`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.writeString(root.resolve("notes.md"), "one\n")
    Files.createDirectory(temporary.resolve("elsewhere"))
    Files.createSymbolicLink(root.resolve("escape"), temporary.resolve("elsewhere"))

    val traversal = assertIs<Outcome.Failed>(operations.perform(Operation.ListDirectory("api", "..")))
    assertEquals(Failure.OutsideRoot("api", ".."), traversal.reason)
    val escaped = assertIs<Outcome.Failed>(operations.perform(Operation.ListDirectory("api", "escape")))
    assertEquals(Failure.OutsideRoot("api", "escape", "escape"), escaped.reason)
    val file = assertIs<Outcome.Failed>(operations.perform(Operation.ListDirectory("api", "notes.md")))
    assertEquals(Failure.NotADirectory("api", "notes.md"), file.reason)
  }

  @Test
  fun `list_directory is bounded by the byte cap and says how many entries there were`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    val name = "e".repeat(100)
    repeat(2000) { Files.writeString(root.resolve("$name-$it.txt"), "x") }

    val listing = assertIs<Outcome.Ok<DirectoryListing>>(operations.perform(Operation.ListDirectory("api"))).value
    assertEquals(2000, listing.totalEntries)
    assertTrue(listing.cappedByBytes, "a listing the cap ended must say so")
    assertTrue(listing.entries.size < 2000)
    assertTrue(listing.entries.sumOf { it.name.toByteArray().size } <= Operation.OUTPUT_CAP_BYTES)
  }

  @Test
  fun `inside a repository search uses the project's own ignore rules`() = runBlocking<Unit> {
    assumeTrue(gitInstalled(), "git is not installed")
    val (root, operations) = workspace()
    git(root, "init", "--quiet")
    Files.writeString(root.resolve(".gitignore"), "ignored.txt\n")
    Files.writeString(root.resolve("tracked.txt"), "the needle is here\n")
    Files.writeString(root.resolve("untracked.txt"), "the needle is here too\n")
    Files.writeString(root.resolve("ignored.txt"), "the needle is here as well\n")
    git(root, "add", "tracked.txt")

    val found = searched(operations, Operation.Search("api", "needle"))
    assertEquals(Enumeration.GitIgnoreRules, found.enumeration)
    assertEquals(setOf("tracked.txt", "untracked.txt"), found.paths())
    assertTrue(found.bounds.complete)
  }

  @Test
  fun `a Root inside a larger repository is enumerated scoped to the Root`() = runBlocking<Unit> {
    assumeTrue(gitInstalled(), "git is not installed")
    // The Root is a subdirectory of the repository, which the Git tools are scoped to.
    // `git ls-files` run in the Root is that scoping without a pathspec to get wrong, and the
    // names it prints are already relative to the Root.
    val outer = Files.createDirectory(temporary.resolve("outer"))
    git(outer, "init", "--quiet")
    Files.writeString(outer.resolve(".gitignore"), "ignored.txt\n")
    Files.writeString(outer.resolve("above.txt"), "the needle is above the Root\n")
    val root = Files.createDirectory(outer.resolve("inner"))
    Files.writeString(root.resolve("tracked.txt"), "the needle is here\n")
    Files.writeString(root.resolve("untracked.txt"), "the needle is here too\n")
    Files.writeString(root.resolve("ignored.txt"), "the needle is here as well\n")
    git(outer, "add", "inner/tracked.txt")
    val registry = WorkspaceRegistry(temporary.resolve("inner-registry.properties"))
    val activity = Activity(temporary.resolve("inner-activity"))
    registry.perform(ManagementAct.Register(root.toString(), "inner"))
    val operations = WorkspaceOperationsPipeline(registry, activity).operationsFor(Origin.ChatGpt)

    val found = searched(operations, Operation.Search("inner", "needle"))
    assertEquals(Enumeration.GitIgnoreRules, found.enumeration)
    assertEquals(setOf("tracked.txt", "untracked.txt"), found.paths())
  }

  @Test
  fun `outside a repository search walks plainly and skips dot-git`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    // Not a repository git will speak for — which is the fallback, and the `.git/` it skips.
    Files.createDirectory(root.resolve(".git"))
    Files.writeString(root.resolve(".git/config"), "needle\n")
    Files.createDirectory(root.resolve("src"))
    Files.writeString(root.resolve("src/main.kt"), "val needle = 1\n")

    val found = searched(operations, Operation.Search("api", "needle"))
    assertEquals(Enumeration.PlainWalk, found.enumeration)
    assertEquals(setOf("src/main.kt"), found.paths())
    assertEquals(1, found.filesSearched)
  }

  @Test
  fun `a repository whose ignore rules cannot be read is refused, never walked`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    // Git's layout is there — HEAD, objects, refs — so this is a repository by the only test
    // that does not need `git` installed. Git itself refuses it, which is the whole point: the
    // ignore rules exist and cannot be read.
    Files.createDirectories(root.resolve(".git/objects"))
    Files.createDirectory(root.resolve(".git/refs"))
    Files.writeString(root.resolve(".git/HEAD"), "garbage\n")
    Files.writeString(root.resolve(".gitignore"), "secrets.env\n")
    Files.writeString(root.resolve("secrets.env"), "token = needle\n")

    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.Search("api", "needle")))
    assertEquals(Failure.GitUnavailable("api", root.toString()), refused.reason)
    assertContains(refused.message, "ignore rules")
    // The whole reason to refuse: walking would have handed the ignored file straight back.
    assertFalse(refused.message.contains("token"))
  }

  @Test
  fun `a tree of directories alone still runs out of time`() = runBlocking<Unit> {
    val (root, operations) = workspace("dirs", budget = 1.milliseconds)
    // Not one file to check a deadline against, which is what made this unbounded before.
    repeat(2000) { Files.createDirectory(root.resolve("empty-$it")) }

    val found = searched(operations, Operation.Search("dirs", "needle"))
    assertTrue(found.bounds.cappedByTime, "a walk of directories alone must still be bounded")
    assertEquals(0, found.filesSearched)
  }

  @Test
  fun `a line too long to hold whole is counted rather than silently unsearched`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    // The query sits past the point one line is held to, so it is genuinely not found — and a
    // model must not read that as "absent from this project".
    val line = "x".repeat(Operation.OUTPUT_CAP_BYTES + 1000) + "needle"
    Files.writeString(root.resolve("minified.js"), line + "\n")

    val found = searched(operations, Operation.Search("api", "needle"))
    assertTrue(found.hits.isEmpty())
    assertEquals(1, found.bounds.linesNotFullySearched)
    assertFalse(found.bounds.complete, "a search that could not read a whole line is not complete")
  }

  @Test
  fun `a listing too long for the cap keeps both ends`() = runBlocking<Unit> {
    val (root, operations) = workspace("wide-dir")
    val padding = "e".repeat(100)
    repeat(2000) { Files.writeString(root.resolve("$padding-${it.toString().padStart(4, '0')}.txt"), "x") }

    val listing = assertIs<Outcome.Ok<DirectoryListing>>(
      operations.perform(Operation.ListDirectory("wide-dir")),
    ).value
    assertEquals(2000, listing.totalEntries)
    assertTrue(listing.droppedBytes > 0, "the marker must name how many bytes were dropped")
    assertEquals("$padding-0000.txt", listing.entries.first().name)
    assertEquals("$padding-1999.txt", listing.entries.last().name, "the tail is kept, not only the head")
  }

  @Test
  fun `a filename match and a content match are both hits`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.writeString(root.resolve("needle.txt"), "nothing of interest\n")
    Files.writeString(root.resolve("haystack.txt"), "first\nthe needle\nlast\n")

    val found = searched(operations, Operation.Search("api", "needle"))
    assertContains(found.hits, SearchHit.Name("needle.txt"))
    assertContains(found.hits, SearchHit.Content("haystack.txt", 2, "the needle", false))
  }

  @Test
  fun `matching is literal until the regex flag is set`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.writeString(root.resolve("code.txt"), "call a.c here\ncall abc here\n")

    val literal = searched(operations, Operation.Search("api", "a.c"))
    assertEquals(listOf(1), literal.hits.filterIsInstance<SearchHit.Content>().map { it.line })

    val expression = searched(operations, Operation.Search("api", "a.c", regex = true))
    assertEquals(listOf(1, 2), expression.hits.filterIsInstance<SearchHit.Content>().map { it.line })

    val broken = assertIs<Outcome.Failed>(operations.perform(Operation.Search("api", "a(", regex = true)))
    assertEquals(Failure.InvalidArgument("query"), broken.reason)
    val empty = assertIs<Outcome.Failed>(operations.perform(Operation.Search("api", "")))
    assertEquals(Failure.InvalidArgument("query"), empty.reason)
  }

  @Test
  fun `the case-sensitivity flag changes the result set`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.writeString(root.resolve("mixed.txt"), "a Needle\na needle\n")

    val insensitive = searched(operations, Operation.Search("api", "needle"))
    assertEquals(listOf(1, 2), insensitive.hits.filterIsInstance<SearchHit.Content>().map { it.line })

    val sensitive = searched(operations, Operation.Search("api", "needle", caseSensitive = true))
    assertEquals(listOf(2), sensitive.hits.filterIsInstance<SearchHit.Content>().map { it.line })
  }

  @Test
  fun `a binary file containing the query is skipped on NUL detection`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.write(root.resolve("image.bin"), "needle\n".toByteArray() + byteArrayOf(0) + "needle\n".toByteArray())
    Files.writeString(root.resolve("text.txt"), "needle\n")

    val found = searched(operations, Operation.Search("api", "needle"))
    assertEquals(setOf("text.txt"), found.paths())

    // The name matched, not the bytes: a binary file's contents are skipped, not its filename.
    Files.write(root.resolve("needle.png"), byteArrayOf(0x89.toByte(), 0x50, 0, 0x4E))
    assertContains(searched(operations, Operation.Search("api", "needle")).hits, SearchHit.Name("needle.png"))

    // A NUL past the head is no less binary: what was found before it is dropped with the file.
    Files.write(
      root.resolve("late.bin"),
      "needle\n".repeat(5000).toByteArray() + byteArrayOf(0),
    )
    assertEquals(
      setOf("text.txt", "needle.png"),
      searched(operations, Operation.Search("api", "needle")).paths(),
    )
  }

  @Test
  fun `a symlink pointing outside the Root is skipped rather than followed`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    val elsewhere = Files.createDirectory(temporary.resolve("elsewhere"))
    Files.writeString(elsewhere.resolve("secret.txt"), "the needle is out here\n")
    Files.createSymbolicLink(root.resolve("escape.txt"), elsewhere.resolve("secret.txt"))
    Files.createSymbolicLink(root.resolve("escape-dir"), elsewhere)
    Files.writeString(root.resolve("own.txt"), "the needle is in here\n")
    // A link that stays inside the Root is ordinary, and is followed.
    Files.createSymbolicLink(root.resolve("inside.txt"), root.resolve("own.txt"))

    val found = searched(operations, Operation.Search("api", "needle"))
    assertEquals(setOf("own.txt", "inside.txt"), found.paths())
  }

  @Test
  fun `exceeding the result cap is an ok with an explicit marker`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.writeString(root.resolve("many.txt"), "needle\n".repeat(Operation.SEARCH_RESULT_CAP + 50))

    val found = searched(operations, Operation.Search("api", "needle"))
    assertEquals(Operation.SEARCH_RESULT_CAP, found.hits.size)
    assertTrue(found.bounds.cappedByResults, "a search the cap ended must say so")
    assertFalse(found.bounds.complete)
    assertFalse(found.bounds.cappedByTime)
  }

  @Test
  fun `exceeding the time budget is an ok truncated by time, never an Uncertain`() = runBlocking<Unit> {
    val (root, operations) = workspace(budget = Duration.ZERO)
    Files.writeString(root.resolve("needle.txt"), "needle\n")

    val outcome = operations.perform(Operation.Search("api", "needle"))
    val found = assertIs<Outcome.Ok<SearchResults>>(outcome).value
    assertTrue(found.bounds.cappedByTime, "a search the budget ended must say so")
    assertFalse(found.bounds.complete)

    // And spent while the walk is under way rather than before it began, which is the path a
    // real search runs out of time on.
    val (many, hurried) = workspace("hurried", budget = 1.milliseconds)
    repeat(2000) { Files.writeString(many.resolve("file-$it.txt"), "a line with a needle in it\n") }
    val underWay = searched(hurried, Operation.Search("hurried", "needle"))
    assertTrue(underWay.bounds.cappedByTime)
    assertTrue(underWay.filesSearched < 2000, "the budget, not the project, should have ended this")

    // And a budget that is not spent leaves no marker behind.
    val (other, unhurried) = workspace("unhurried", budget = 30.seconds)
    Files.writeString(other.resolve("needle.txt"), "needle\n")
    assertFalse(searched(unhurried, Operation.Search("unhurried", "needle")).bounds.cappedByTime)
  }

  @Test
  fun `output is cut head and tail, and the marker names the dropped byte count`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    val line = "needle " + "x".repeat(Operation.SEARCH_LINE_CAP_BYTES)
    Files.writeString(root.resolve("wide.txt"), (1..Operation.SEARCH_RESULT_CAP).joinToString("\n") { line } + "\n")

    val found = searched(operations, Operation.Search("api", "needle"))
    val contents = found.hits.filterIsInstance<SearchHit.Content>()
    assertFalse(found.bounds.cappedByResults, "the byte bound, not the result cap, should have bound")
    assertTrue(found.hits.size < Operation.SEARCH_RESULT_CAP)
    assertEquals(Operation.SEARCH_RESULT_CAP - found.hits.size, found.bounds.droppedHits)
    assertTrue(found.bounds.droppedBytes > 0, "the marker must name how many bytes were dropped")
    // Both ends are kept: the middle is what went.
    assertEquals(1, contents.first().line)
    assertEquals(Operation.SEARCH_RESULT_CAP, contents.last().line)
    assertTrue(contents.all { it.lineCut }, "a line longer than the quote bound is quoted cut")
    val carried = found.hits.sumOf { it.path.toByteArray().size } +
      contents.sumOf { it.text.toByteArray().size }
    assertTrue(carried <= Operation.OUTPUT_CAP_BYTES, "returned $carried bytes")
  }

  @Test
  fun `search is confined to the Root and to the subtree it is given`() = runBlocking<Unit> {
    val (root, operations) = workspace()
    Files.createDirectory(root.resolve("src"))
    Files.writeString(root.resolve("src/main.kt"), "needle\n")
    Files.writeString(root.resolve("other.txt"), "needle\n")

    assertEquals(setOf("src/main.kt"), searched(operations, Operation.Search("api", "needle", "src")).paths())
    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.Search("api", "needle", "..")))
    assertEquals(Failure.OutsideRoot("api", ".."), refused.reason)
    val notADirectory = assertIs<Outcome.Failed>(operations.perform(Operation.Search("api", "needle", "other.txt")))
    assertEquals(Failure.NotADirectory("api", "other.txt"), notADirectory.reason)
  }

  @Test
  fun `both tools are recorded in Activity with what they were asked and what they found`() = runBlocking<Unit> {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    val root = Files.createDirectory(temporary.resolve("project"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val operations = WorkspaceOperationsPipeline(registry, activity).operationsFor(Origin.ChatGpt)
    Files.writeString(root.resolve("needle.txt"), "needle\n")

    operations.perform(Operation.ListDirectory("api"))
    operations.perform(Operation.Search("api", "needle", regex = true))

    val entries = activity.entries()
    assertEquals(listOf("list_directory", "search"), entries.map { it.tool })
    assertEquals("path=.", entries.first().arguments)
    assertEquals("query=needle regex", entries.last().arguments)
    assertEquals(ActivityOutcome.Ok("1 of 1 entries"), entries.first().outcome)
    assertEquals(ActivityOutcome.Ok("2 hits in 1 file"), entries.last().outcome)
  }
}
