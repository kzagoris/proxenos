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

/**
 * The three Git tools (SPEC §4). Run against a real `git` and real temporary repositories, per
 * [ADR 0002](../../../../../../../../docs/adr/0002-no-abstraction-below-the-core.md): a fake
 * `git` would agree with whatever this file believed about porcelain, which is the one thing
 * worth checking.
 *
 * The decision under test is **scoping**. Where the Root sits inside a larger repository the
 * answer covers the Root alone and says so — an empty `git_status` that did not say it had been
 * scoped reads as "the repository is clean", and that label is the whole point of the
 * arrangement rather than a nicety.
 */
class GitToolsTest {
  @TempDir
  lateinit var temporary: Path

  /** A readable Workspace over [root], and the pipeline reaching it. */
  private suspend fun workspace(
    root: Path,
    name: String = "api",
    git: GitTools = GitTools(),
  ): Pair<WorkspaceRegistry, WorkspaceOperations> {
    val registry = WorkspaceRegistry(temporary.resolve("$name-registry.properties"))
    val activity = Activity(temporary.resolve("$name-activity"))
    registry.perform(ManagementAct.Register(root.toString(), name))
    val pipeline = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(), git)
    return registry to pipeline.operationsFor(Origin.ChatGpt)
  }

  private fun directory(name: String): Path = Files.createDirectories(temporary.resolve(name))

  /**
   * Every invocation carries an identity and turns off signing, so that a developer's own
   * global config cannot decide whether this suite passes.
   */
  private fun git(at: Path, vararg arguments: String) {
    val process = ProcessBuilder(
      listOf(
        "git", "-c", "user.name=Ada Lovelace", "-c", "user.email=ada@example.com",
        "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main", *arguments,
      ),
    ).directory(at.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    assertTrue(process.waitFor(30, SECONDS))
    assertEquals(0, process.exitValue(), "git ${arguments.joinToString(" ")} failed")
  }

  /** A repository at [at] with one commit, so `HEAD` exists for the tools that need one. */
  private fun repository(at: Path) {
    git(at, "init", "--quiet")
    Files.writeString(at.resolve("README.md"), "one\n")
    git(at, "add", "README.md")
    git(at, "commit", "--quiet", "-m", "first")
  }

  private fun gitInstalled(): Boolean = try {
    ProcessBuilder("git", "--version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
  } catch (_: IOException) {
    false
  }

  @BeforeTest
  fun requireGit() = assumeTrue(gitInstalled(), "git is not installed")

  private suspend fun <R> ok(operations: WorkspaceOperations, op: Operation<R>): R =
    assertIs<Outcome.Ok<R>>(operations.perform(op)).value

  @Test
  fun `git_status is parsed from porcelain, with Git's own two status characters`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root)
    Files.writeString(root.resolve("README.md"), "one\ntwo\n")
    Files.writeString(root.resolve("added.txt"), "new\n")
    git(root, "add", "added.txt")
    Files.writeString(root.resolve("untracked.txt"), "loose\n")

    val report = ok(operations, Operation.GitStatus("api"))
    assertNull(report.scope.scopedTo, "the Root is the repository root, so nothing was scoped")
    assertEquals(root.toRealPath().toString(), report.scope.repository)
    assertEquals(3, report.totalEntries)
    assertFalse(report.cappedByBytes)
    assertEquals(
      mapOf("README.md" to " M", "added.txt" to "A ", "untracked.txt" to "??"),
      report.entries.associate { it.path to "${it.index}${it.workTree}" },
    )
    assertTrue(report.entries.all { it.originalPath == null })
  }

  @Test
  fun `git_status names where a rename came from`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root)
    git(root, "mv", "README.md", "GUIDE.md")

    val entry = ok(operations, Operation.GitStatus("api")).entries.single()
    assertEquals("GUIDE.md", entry.path)
    assertEquals("README.md", entry.originalPath)
    assertEquals('R', entry.index)
  }

  @Test
  fun `a Root inside a larger repository is scoped to the Root and says so`() = runBlocking<Unit> {
    val repository = directory("monorepo")
    repository(repository)
    val root = Files.createDirectory(repository.resolve("service"))
    Files.writeString(root.resolve("main.kt"), "fun main() {}\n")
    git(repository, "add", "-A")
    git(repository, "commit", "--quiet", "-m", "the service")
    val (_, operations) = workspace(root)

    // One change inside the Root, one outside it. Only the first may be spoken of.
    Files.writeString(root.resolve("main.kt"), "fun main() { println() }\n")
    Files.writeString(repository.resolve("README.md"), "changed elsewhere\n")

    val status = ok(operations, Operation.GitStatus("api"))
    assertEquals("service", status.scope.scopedTo)
    assertTrue(status.scope.scoped)
    assertEquals(repository.toRealPath().toString(), status.scope.repository)
    // Root-relative, so it is a path `read_file` can be called with.
    assertEquals(listOf("main.kt"), status.entries.map { it.path })

    val diff = ok(operations, Operation.GitDiff("api"))
    assertEquals("service", diff.scope.scopedTo)
    assertTrue("service/main.kt" in diff.patch)
    assertFalse("README.md" in diff.patch, "a change outside the Root is not this Root's to show")
  }

  @Test
  fun `an empty status against a scoped Root is not a clean repository`() = runBlocking<Unit> {
    val repository = directory("monorepo")
    repository(repository)
    val root = Files.createDirectory(repository.resolve("service"))
    Files.writeString(root.resolve("main.kt"), "fun main() {}\n")
    git(repository, "add", "-A")
    git(repository, "commit", "--quiet", "-m", "the service")
    val (_, operations) = workspace(root)
    Files.writeString(repository.resolve("README.md"), "changed elsewhere\n")

    val status = ok(operations, Operation.GitStatus("api"))
    assertTrue(status.entries.isEmpty(), "the only change is outside the Root")
    // The label is what stops this being read as "the repository is clean".
    assertEquals("service", status.scope.scopedTo)
  }

  @Test
  fun `git_diff is the working tree against HEAD, and staged narrows it`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root)
    Files.writeString(root.resolve("staged.txt"), "staged\n")
    git(root, "add", "staged.txt")
    Files.writeString(root.resolve("README.md"), "one\nunstaged\n")

    val both = ok(operations, Operation.GitDiff("api"))
    assertFalse(both.staged)
    assertTrue("staged.txt" in both.patch, "the default carries staged changes")
    assertTrue("unstaged" in both.patch, "and unstaged ones, together")
    assertTrue("a/README.md" in both.patch, "a patch looks like a patch whatever the user's config says")
    assertFalse(both.cappedByBytes)

    val staged = ok(operations, Operation.GitDiff("api", staged = true))
    assertTrue(staged.staged)
    assertTrue("staged.txt" in staged.patch)
    assertFalse("unstaged" in staged.patch, "staged asked for the staged changes alone")
  }

  @Test
  fun `git_log defaults to 20 commits with hash, subject, author and date`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root)
    repeat(25) { number ->
      Files.writeString(root.resolve("file-$number.txt"), "$number\n")
      git(root, "add", "-A")
      git(root, "commit", "--quiet", "-m", "commit number $number")
    }

    val log = ok(operations, Operation.GitLog("api"))
    assertEquals(Operation.GIT_LOG_COMMITS, log.commits.size)
    assertFalse(log.cappedByBytes)
    val newest = log.commits.first()
    assertEquals("commit number 24", newest.subject)
    assertEquals("Ada Lovelace", newest.author)
    assertEquals(40, newest.hash.length, "the full hash: an abbreviation can collide")
    assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})$""").matches(newest.date), newest.date)
    // Newest first, which is the end a log is read from.
    assertEquals("commit number 5", log.commits.last().subject)

    val short = ok(operations, Operation.GitLog("api", limit = 3))
    assertEquals(listOf("commit number 24", "commit number 23", "commit number 22"), short.commits.map { it.subject })

    val invalid = assertIs<Outcome.Failed>(operations.perform(Operation.GitLog("api", limit = 0)))
    assertEquals(Failure.InvalidArgument("limit"), invalid.reason)
  }

  @Test
  fun `git_log is scoped to the Root when it sits inside a larger repository`() = runBlocking<Unit> {
    val repository = directory("monorepo")
    repository(repository)
    val root = Files.createDirectory(repository.resolve("service"))
    Files.writeString(root.resolve("main.kt"), "fun main() {}\n")
    git(repository, "add", "-A")
    git(repository, "commit", "--quiet", "-m", "the service")
    Files.writeString(repository.resolve("elsewhere.txt"), "not ours\n")
    git(repository, "add", "-A")
    git(repository, "commit", "--quiet", "-m", "somebody else's work")
    val (_, operations) = workspace(root)

    val log = ok(operations, Operation.GitLog("api"))
    assertEquals("service", log.scope.scopedTo)
    assertEquals(listOf("the service"), log.commits.map { it.subject })
  }

  @Test
  fun `with no repository above the Root every Git tool fails plainly and stays in the catalog`() =
    runBlocking<Unit> {
      val root = directory("loose")
      val (_, operations) = workspace(root)

      for (op in listOf(Operation.GitStatus("api"), Operation.GitDiff("api"), Operation.GitLog("api"))) {
        val failed = assertIs<Outcome.Failed>(operations.perform(op), "$op")
        assertEquals(Failure.NoRepository("api", root.toString()), failed.reason)
      }
      // Flat and static (§3): the entries are there whether or not there is anything to read.
      assertTrue(operations.catalog.map { it.name }.containsAll(listOf("git_status", "git_diff", "git_log")))
    }

  @Test
  fun `with no usable git every Git tool takes the same plain error path`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root, git = GitTools(program = "git-that-is-not-installed"))

    for (op in listOf(Operation.GitStatus("api"), Operation.GitDiff("api"), Operation.GitLog("api"))) {
      val failed = assertIs<Outcome.Failed>(operations.perform(op), "$op")
      // A plain `failed` like "no repository": never Uncertain, because a read changed nothing.
      assertEquals(Failure.GitUnavailable("api", root.toRealPath().toString()), failed.reason)
      assertTrue("not installed" in failed.message, failed.message)
    }
  }

  @Test
  fun `a Git invocation that outruns its budget fails plainly rather than hanging`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root, git = GitTools(budget = Duration.ZERO))

    val failed = assertIs<Outcome.Failed>(operations.perform(Operation.GitStatus("api")))
    assertEquals(Failure.GitUnavailable("api", root.toRealPath().toString()), failed.reason)
    assertTrue("did not answer" in failed.message, failed.message)
  }

  @Test
  fun `the chokepoint runs at the repository root, with the flag and the environment §4 fixes`() {
    // The argument vectors the three tools really send are checked above, against a recording
    // `git`. This is the shape of the chokepoint itself, which is where those two arrive.
    val builder = GitTools().builder(temporary, listOf("status"))
    assertEquals("--no-optional-locks", builder.command()[1])
    // §4: a repository needing credentials fails instead of hanging on a prompt nobody can see.
    assertEquals("0", builder.environment()["GIT_TERMINAL_PROMPT"])
    assertEquals(listOf("-C", temporary.toString()), builder.command().subList(2, 4))
  }

  @Test
  fun `the Git tools sit at Read, and a Workspace at None answers as though it did not exist`() =
    runBlocking<Unit> {
      val root = directory("project")
      repository(root)
      val (registry, operations) = workspace(root)
      val workspace = registry.perform(ManagementAct.Register(directory("second").toString(), "withheld"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))

      // Read is enough: showing what changed does not require granting command execution.
      assertIs<Outcome.Ok<GitStatusReport>>(operations.perform(Operation.GitStatus("api")))
      for (op in listOf(
        Operation.GitStatus("withheld"), Operation.GitDiff("withheld"), Operation.GitLog("withheld"),
      )) {
        val failed = assertIs<Outcome.Failed>(operations.perform(op), "$op")
        assertIs<Failure.NoSuchWorkspace>(failed.reason, "$op")
      }
    }

  @Test
  fun `a diff past the byte cap is cut at both ends and says how much went`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root)
    // One line per number, so both ends of the patch name a line this test can look for.
    Files.writeString(root.resolve("big.txt"), (1..40_000).joinToString("\n") { "line $it" } + "\n")
    // Staged, because `git diff HEAD` is a diff of what Git tracks — an untracked file is
    // `git_status`'s to report, not this tool's.
    git(root, "add", "big.txt")

    val diff = ok(operations, Operation.GitDiff("api"))
    assertTrue(diff.cappedByBytes, "a patch the cap ended must say so")
    assertTrue(diff.patch.utf8Size() <= Operation.OUTPUT_CAP_BYTES)
    assertTrue("+line 1\n" in diff.patch, "the head is where the patch said what it was doing")
    assertTrue("+line 40000\n" in diff.patch, "the tail is how it ended")
    assertFalse("+line 20000\n" in diff.patch, "the middle is what the cap dropped")
    assertTrue(diff.droppedBytes > 0)
  }

  @Test
  fun `every invocation the three tools make carries the flag and the environment`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    // A `git` that answers nothing and writes down what it was asked. Real argument vectors from
    // the real call sites, rather than one builder this test happens to construct itself.
    val recorded = temporary.resolve("invocations")
    val fake = temporary.resolve("recording-git")
    Files.writeString(
      fake,
      """
      #!/bin/sh
      printf '%s\n' "$@" >> '$recorded'
      printf 'TERMINAL_PROMPT=%s\n' "${'$'}{GIT_TERMINAL_PROMPT-unset}" >> '$recorded'
      printf -- '--- end\n' >> '$recorded'
      """.trimIndent(),
    )
    fake.toFile().setExecutable(true)
    val (_, operations) = workspace(root, git = GitTools(program = fake.toString()))

    operations.perform(Operation.GitStatus("api"))
    operations.perform(Operation.GitDiff("api"))
    operations.perform(Operation.GitLog("api"))

    val invocations = Files.readString(recorded).split("--- end\n").filter { it.isNotBlank() }
    assertEquals(3, invocations.size)
    for (invocation in invocations) {
      val arguments = invocation.lines()
      assertEquals("--no-optional-locks", arguments.first(), invocation)
      assertTrue("TERMINAL_PROMPT=0" in arguments, invocation)
    }
  }

  @Test
  fun `a status past the byte cap says how many entries there were`() = runBlocking<Unit> {
    val root = directory("project")
    repository(root)
    val (_, operations) = workspace(root)
    val name = "u".repeat(100)
    repeat(2000) { Files.writeString(root.resolve("$name-$it.txt"), "x") }

    val status = ok(operations, Operation.GitStatus("api"))
    assertEquals(2000, status.totalEntries)
    assertTrue(status.cappedByBytes, "a report the cap ended must say so")
    assertTrue(status.entries.size < 2000)
    assertTrue(status.droppedBytes > 0)
  }

  @Test
  fun `a log past the byte cap is cut at the old end, which is the end nobody was reading`() =
    runBlocking<Unit> {
      val root = directory("project")
      repository(root)
      val (_, operations) = workspace(root)
      // Subjects long enough that ten commits do not fit, without ten thousand commits to make.
      repeat(10) { number ->
        Files.writeString(root.resolve("file-$number.txt"), "$number\n")
        git(root, "add", "-A")
        git(root, "commit", "--quiet", "-m", "commit $number " + "and so on ".repeat(900))
      }

      val log = ok(operations, Operation.GitLog("api"))
      assertEquals(11, log.totalCommits, "ten of ours and the one the repository started with")
      assertTrue(log.cappedByBytes)
      assertTrue(log.commits.size < 11)
      assertTrue(log.commits.first().subject.startsWith("commit 9"), "the newest survived")
      assertTrue(log.commits.none { it.subject == "first" }, "the oldest is what went")
    }

  @Test
  fun `a repository with no commits is answered rather than crashed through`() = runBlocking<Unit> {
    val root = directory("fresh")
    git(root, "init", "--quiet")
    Files.writeString(root.resolve("started.txt"), "one\n")
    val (_, operations) = workspace(root)

    // Status has no HEAD to need, so it answers: the file is untracked and says so.
    val status = ok(operations, Operation.GitStatus("api"))
    assertEquals(listOf("started.txt"), status.entries.map { it.path })

    // The other two are `HEAD`-relative, and Git's own complaint is better than ours. A plain
    // `failed` either way — a read guarantees nothing on disk changed (ADR 0001).
    for (op in listOf(Operation.GitDiff("api"), Operation.GitLog("api"))) {
      val failed = assertIs<Outcome.Failed>(operations.perform(op), "$op")
      assertEquals(Failure.GitUnavailable("api", root.toRealPath().toString()), failed.reason)
      assertTrue("git refused" in failed.message, failed.message)
    }
  }

  @Test
  fun `the account names the scope, so an unattended read is not misread later`() = runBlocking<Unit> {
    val repository = directory("monorepo")
    repository(repository)
    val root = Files.createDirectory(repository.resolve("service"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val operations = WorkspaceOperationsPipeline(registry, activity).operationsFor(Origin.ChatGpt)

    assertIs<Outcome.Ok<GitStatusReport>>(operations.perform(Operation.GitStatus("api")))
    val entry = activity.entries().single()
    assertEquals("git_status", entry.tool)
    val outcome = assertIs<ActivityOutcome.Ok>(entry.outcome)
    assertTrue("scoped to 'service'" in outcome.detail, outcome.detail)
  }
}
