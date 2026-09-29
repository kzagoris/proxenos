package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class WorkspaceOperationsPipelineTest {
  @TempDir
  lateinit var temporary: Path

  private fun pipeline(): Pair<WorkspaceRegistry, WorkspaceOperations> {
    val wiring = wiring()
    return wiring.registry to wiring.operations
  }

  /** What the composition root does (SPEC §9): one pipeline, and one view per surface. */
  private fun wiring(origin: Origin = Origin.ChatGpt): Wiring {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    val pipeline = WorkspaceOperationsPipeline(registry, activity)
    return Wiring(registry, activity, pipeline, pipeline.operationsFor(origin))
  }

  /**
   * The same wiring with the command budget and the kill's grace shortened (ADR 0002): real
   * child processes, and configuration where a test would otherwise wait out 45 seconds.
   */
  private fun commandWiring(budget: Duration = 30.seconds, feed: RuntimeFeed = RuntimeFeed()): Wiring {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"), feed)
    val activity = Activity(temporary.resolve("activity"), feed = feed)
    val pipeline = WorkspaceOperationsPipeline(
      registry, activity, Operation.SEARCH_BUDGET, PathLocks(), GitTools(),
      commandBudget = budget, runner = CommandRunner(grace = 500.milliseconds),
    )
    return Wiring(registry, activity, pipeline, pipeline.operationsFor(Origin.ChatGpt))
  }

  private suspend fun commandable(registry: WorkspaceRegistry, root: Path, name: String = "api"): Workspace {
    val workspace = registry.perform(ManagementAct.Register(root.toString(), name))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    return workspace
  }

  private data class Wiring(
    val registry: WorkspaceRegistry,
    val activity: Activity,
    /** The Runtime itself, which is what Stop is driven through (SPEC §6.3, §8.1). */
    val pipeline: WorkspaceOperationsPipeline,
    val operations: WorkspaceOperations,
  )

  private fun root(name: String = "project"): Path = Files.createDirectory(temporary.resolve(name))

  /** A command that finished inside its own call, which is what almost every command does. */
  private fun finished(outcome: Outcome<CommandReply>): CommandResult =
    assertIs<CommandReply.Finished>(assertIs<Outcome.Ok<CommandReply>>(outcome).value).result

  private suspend fun <R> WorkspaceOperations.ok(op: Operation<R>): R =
    assertIs<Outcome.Ok<R>>(perform(op)).value

  private suspend fun readable(registry: WorkspaceRegistry, root: Path, name: String = "api") {
    registry.perform(ManagementAct.Register(root.toString(), name))
  }

  @Test
  fun `the catalog is data, flat, and carries each entry's required Access Level`() {
    val catalog = pipeline().second.catalog
    assertEquals(
      listOf(
        "list_workspaces", "list_directory", "read_file", "search", "git_status", "git_diff",
        "git_log", "write_file", "edit_file", "run_command", "get_result",
      ),
      catalog.map { it.name },
    )
    assertEquals(
      mapOf(
        "list_workspaces" to null,
        "list_directory" to AccessLevel.Read,
        "read_file" to AccessLevel.Read,
        "search" to AccessLevel.Read,
        "git_status" to AccessLevel.Read,
        "git_diff" to AccessLevel.Read,
        "git_log" to AccessLevel.Read,
        "write_file" to AccessLevel.Write,
        "edit_file" to AccessLevel.Write,
        "run_command" to AccessLevel.Command,
        "get_result" to AccessLevel.Command,
      ),
      catalog.associate { it.name to it.requiredLevel },
    )
    // §6.4: the wording of the key's description is the only lever on a repeat the model
    // initiates itself, so it is a decision, not a detail.
    val key = catalog.single { it.name == "write_file" }.arguments.single { it.name == "request_id" }
    assertContains(key.description, "same request_id")
    // Every scoped entry names its Workspace, and the three mutating ones take a Delivery key.
    for (entry in catalog.filter { it.name != "list_workspaces" }) {
      val first = entry.arguments.first()
      assertEquals(ArgumentSpec("workspace", ArgumentType.Text, true, first.description), first)
    }
    assertEquals(
      listOf("write_file", "edit_file", "run_command"),
      catalog.filter { spec -> spec.arguments.any { it.name == "request_id" } }.map { it.name },
    )
    // §6.2: promotion is automatic and never requested, and the model cannot buy time the
    // transport will not give it — so neither argument exists to be asked for.
    val asked = catalog.flatMap { it.arguments }.map { it.name }
    listOf("background", "timeout", "timeout_seconds", "async", "detach").forEach {
      assertFalse(it in asked, "'$it' is not an argument any tool takes")
    }
  }

  @Test
  fun `scenario 6 exercises all eleven Operations through one transport-free surface`() = runBlocking<Unit> {
    val (registry, activity, pipeline, operations) = commandWiring(budget = 20.milliseconds)
    val root = root()
    val file = root.resolve("notes.md")
    Files.writeString(file, "original needle\n")
    val workspace = commandable(registry, root)

    fun git(vararg args: String) {
      val process = ProcessBuilder(listOf("git", *args)).directory(root.toFile())
        .redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")}: $output")
    }
    git("init", "--quiet")
    git("add", "notes.md")
    git("-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
      "commit", "--quiet", "-m", "initial")

    assertEquals(listOf("api"), operations.ok(Operation.ListWorkspaces).map { it.name })
    assertTrue(operations.ok(Operation.ListDirectory("api")).entries.any { it.name == "notes.md" })
    assertContains(operations.ok(Operation.ReadFile("api", "notes.md")).text, "original needle")
    assertTrue(operations.ok(Operation.Search("api", "needle")).hits.any { it.path == "notes.md" })

    assertEquals("notes.md", operations.ok(Operation.WriteFile("api", "notes.md", "changed needle\n", "write-6")).path)
    assertEquals("notes.md", operations.ok(Operation.EditFile("api", "notes.md", "changed", "edited", "edit-6")).path)
    assertEquals("edited needle\n", Files.readString(file))

    assertTrue(operations.ok(Operation.GitStatus("api")).entries.any { it.path == "notes.md" })
    assertContains(operations.ok(Operation.GitDiff("api")).patch, "edited needle")
    assertTrue(operations.ok(Operation.GitLog("api")).commits.isNotEmpty())

    val promoted = assertIs<CommandReply.Promoted>(
      operations.ok(Operation.RunCommand("api", "sleep 0.5; printf 'scenario-6\\n'", deliveryKey = "command-6")),
    )
    await("scenario 6 command completion") {
      activity.entries().firstOrNull { it.tool == "run_command" && it.outcome is ActivityOutcome.Unclaimed }
    }
    val reached = assertIs<Collected.Reached>(operations.ok(Operation.GetResult("api", promoted.handle.value)))
    assertContains(assertIs<Outcome.Ok<CommandResult>>(reached.outcome).value.output, "scenario-6")

    val recorded = activity.entries()
    assertEquals(11, recorded.size)
    assertEquals(operations.catalog.map { it.name }.toSet(), recorded.map { it.tool }.toSet())
    assertTrue(recorded.all { it.origin == Origin.ChatGpt && it.outcome is ActivityOutcome.Ok })
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
    pipeline.stop()
  }

  @Test
  fun `a scoped call with no workspace argument is refused even when one Workspace is exposed`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.writeString(root.resolve("file.txt"), "contents\n")
    readable(registry, root, "only")

    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile(null, "file.txt")))
    assertEquals(Failure.NoSuchWorkspace(listOf("only")), refused.reason)
    assertContains(refused.message, "only")
    assertContains(refused.message, "workspace")
  }

  @Test
  fun `the Access Level of the named Workspace governs, and list_workspaces needs none`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.writeString(root.resolve("file.txt"), "contents\n")
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))

    val withheld = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "file.txt")))
    assertIs<Failure.NoSuchWorkspace>(withheld.reason)
    assertEquals(Outcome.Ok(emptyList()), operations.perform(Operation.ListWorkspaces))

    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
    assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "file.txt")))
    val listed = assertIs<Outcome.Ok<List<WorkspaceListing>>>(operations.perform(Operation.ListWorkspaces))
    assertEquals(listOf("api"), listed.value.map { it.name })
  }

  @Test
  fun `a path leaving the Root by traversal or by being absolute is OutsideRoot`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    val outside = Files.writeString(temporary.resolve("secret.txt"), "not yours\n")
    readable(registry, root)

    for (path in listOf("../secret.txt", "sub/../../secret.txt", outside.toString())) {
      val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", path)))
      assertEquals(Failure.OutsideRoot("api", path), refused.reason)
      assertContains(refused.message, path)
    }
    // §2.4 rejects traversal as such, including the kind that would have landed back inside.
    Files.writeString(root.resolve("file.txt"), "contents\n")
    Files.createDirectory(root.resolve("sub"))
    val traversal = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "sub/../file.txt")))
    assertEquals(Failure.OutsideRoot("api", "sub/../file.txt"), traversal.reason)
  }

  @Test
  fun `a symlink pointing outside is refused even when its target does not exist yet`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    val elsewhere = Files.createDirectory(temporary.resolve("elsewhere"))
    // Files.exists follows the link, so a dangling one reports that the link is not there.
    // Resolved textually it would read as an ordinary new file inside the Root, and a write
    // through it would land outside it.
    Files.createSymbolicLink(root.resolve("dangling"), elsewhere.resolve("not-yet.txt"))
    readable(registry, root)

    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "dangling")))
    assertEquals(Failure.OutsideRoot("api", "dangling", "dangling"), refused.reason)
    assertContains(refused.message, elsewhere.resolve("not-yet.txt").toString())
  }

  @Test
  fun `a symlink loop is refused rather than followed forever`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.createSymbolicLink(root.resolve("loop"), root.resolve("loop"))
    readable(registry, root)

    assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "loop")))
  }

  @Test
  fun `a symlink is resolved and then checked, and a rejection names the symlink`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    val elsewhere = Files.createDirectory(temporary.resolve("elsewhere"))
    Files.writeString(elsewhere.resolve("secret.txt"), "not yours\n")
    Files.createSymbolicLink(root.resolve("escape"), elsewhere)
    Files.createDirectory(root.resolve("real"))
    Files.writeString(root.resolve("real/file.txt"), "contents\n")
    Files.createSymbolicLink(root.resolve("inside"), root.resolve("real"))
    readable(registry, root)

    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "escape/secret.txt")))
    assertEquals(Failure.OutsideRoot("api", "escape/secret.txt", "escape"), refused.reason)
    assertContains(refused.message, "escape")
    assertContains(refused.message, elsewhere.toString())
    assertContains(refused.message, "Workspace")

    // A symlink that stays inside the Root resolves and is allowed.
    val allowed = assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "inside/file.txt")))
    assertEquals("contents\n", allowed.value.text)
  }
  @Test
  fun `a read is bounded by the line ceiling and reports what it did not return`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    val lines = (1..5000).map { "line $it" }
    Files.writeString(root.resolve("long.txt"), lines.joinToString("\n", postfix = "\n"))
    readable(registry, root)

    val first = assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "long.txt"))).value
    assertEquals(lines.take(2000).joinToString("\n", postfix = "\n"), first.text)
    assertEquals(1, first.firstLine)
    assertEquals(2000, first.lineCount)
    assertEquals(5000, first.totalLines)
    assertFalse(first.cappedByBytes, "the line ceiling ended this read, not the cap")

    val paged = assertIs<Outcome.Ok<FileContent>>(
      operations.perform(Operation.ReadFile("api", "long.txt", offset = 4999)),
    ).value
    assertEquals("line 4999\nline 5000\n", paged.text)
    assertEquals(4999, paged.firstLine)
    assertEquals(2, paged.lineCount)
    assertEquals(5000, paged.totalLines)

    // 2000 is a ceiling, not only a default: asking for more is held to it.
    val asked = assertIs<Outcome.Ok<FileContent>>(
      operations.perform(Operation.ReadFile("api", "long.txt", limit = 3000)),
    ).value
    assertEquals(2000, asked.lineCount)
    assertEquals(5000, asked.totalLines)
  }

  @Test
  fun `the byte cap binds before the line ceiling when the lines are long`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    val lines = (1..400).map { "x".repeat(200) }
    Files.writeString(root.resolve("wide.txt"), lines.joinToString("\n", postfix = "\n"))
    readable(registry, root)

    val content = assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "wide.txt"))).value
    val bytes = content.text.toByteArray().size
    assertEquals(lines.take(content.lineCount).joinToString("\n", postfix = "\n"), content.text)
    assertTrue(content.lineCount < 400, "the cap, not the file, should have ended this read")
    assertTrue(bytes <= Operation.OUTPUT_CAP_BYTES, "returned $bytes bytes")
    assertTrue(bytes + 201 > Operation.OUTPUT_CAP_BYTES, "one more line would still have fitted")
    assertEquals(400, content.totalLines)
    assertTrue(content.cappedByBytes, "a read the cap ended must say so")
  }

  @Test
  fun `the cap bounds what is returned, not what was read`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    // Each 0xFF decodes to U+FFFD, three bytes where the input was one: a read capped on input
    // bytes would return roughly three times the cap to the transport.
    Files.write(root.resolve("malformed.txt"), ByteArray(Operation.OUTPUT_CAP_BYTES) { 0xFF.toByte() })
    // One line and no newline in sight: nothing to buffer this against but the cap itself.
    Files.newBufferedWriter(root.resolve("minified.js")).use { writer ->
      repeat(200) { writer.write("x".repeat(10_000)) }
    }
    readable(registry, root)

    val malformed = assertIs<Outcome.Ok<FileContent>>(
      operations.perform(Operation.ReadFile("api", "malformed.txt")),
    ).value
    assertTrue(malformed.text.toByteArray().size <= Operation.OUTPUT_CAP_BYTES, "returned over the cap")
    assertTrue(malformed.cappedByBytes)

    // A single line longer than the whole cap comes back cut, never as an empty success.
    val minified = assertIs<Outcome.Ok<FileContent>>(
      operations.perform(Operation.ReadFile("api", "minified.js")),
    ).value
    assertEquals(1, minified.lineCount)
    assertEquals(1, minified.totalLines)
    assertTrue(minified.cappedByBytes)
    assertEquals(Operation.OUTPUT_CAP_BYTES, minified.text.toByteArray().size)
  }

  @Test
  fun `a file without a trailing newline still counts its last line`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.writeString(root.resolve("bare.txt"), "first\nsecond")
    Files.writeString(root.resolve("empty.txt"), "")
    readable(registry, root)

    val bare = assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "bare.txt"))).value
    assertEquals("first\nsecond", bare.text)
    assertEquals(2, bare.totalLines)
    val empty = assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "empty.txt"))).value
    assertEquals("", empty.text)
    assertEquals(0, empty.totalLines)
  }

  @Test
  fun `a binary file is refused in plain words instead of returned as replacement characters`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.write(root.resolve("image.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x0D, 0x0A))
    readable(registry, root)

    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "image.png")))
    assertEquals(Failure.Binary("api", "image.png"), refused.reason)
    assertContains(refused.message, "binary")
    assertFalse(refused.message.contains("\uFFFD"))

    // A NUL past the head is no less binary, and the line count already costs a whole scan.
    Files.write(root.resolve("late.bin"), "text\n".repeat(4000).toByteArray() + byteArrayOf(0))
    val late = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "late.bin")))
    assertEquals(Failure.Binary("api", "late.bin"), late.reason)
  }

  @Test
  fun `malformed UTF-8 is replaced rather than thrown`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    // A lone 0xFF is not valid UTF-8 anywhere, and nothing here is a NUL, so this is text.
    Files.write(root.resolve("mixed.txt"), "caf".toByteArray() + byteArrayOf(0xFF.toByte()) + "\ntail\n".toByteArray())
    readable(registry, root)

    val content = assertIs<Outcome.Ok<FileContent>>(operations.perform(Operation.ReadFile("api", "mixed.txt"))).value
    assertEquals("caf\uFFFD\ntail\n", content.text)
    assertEquals(2, content.totalLines)
  }

  @Test
  fun `a missing path, a directory and a nonsense window are Operation-level results`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.createDirectory(root.resolve("sub"))
    Files.writeString(root.resolve("file.txt"), "contents\n")
    readable(registry, root)

    for (path in listOf("missing.txt", "sub")) {
      val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", path)))
      assertEquals(Failure.NotAFile("api", path), refused.reason)
    }
    val badOffset = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "file.txt", offset = 0)))
    assertEquals(Failure.InvalidArgument("offset"), badOffset.reason)
    val badLimit = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "file.txt", limit = 0)))
    assertEquals(Failure.InvalidArgument("limit"), badLimit.reason)
  }

  @Test
  fun `perform does not block the dispatcher it was called from`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    Files.newBufferedWriter(root.resolve("big.txt")).use { writer ->
      repeat(200_000) { writer.write("x".repeat(60)); writer.write("\n") }
    }
    readable(registry, root)

    // One thread, so work that fails to leave it is observable: the probe behind it cannot run
    // until the blocking scan has moved to the core's own I/O dispatcher.
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
      withContext(caller) {
        val read = async { operations.perform(Operation.ReadFile("api", "big.txt")) }
        val probe = launch { }
        probe.join()
        assertFalse(read.isCompleted, "the caller's only thread was held by the read")
        assertEquals(200_000, assertIs<Outcome.Ok<FileContent>>(read.await()).value.totalLines)
      }
    }
  }
  @Test
  fun `an unreadable file is a tool result, not an exception out of the core`() = runBlocking<Unit> {
    val (registry, operations) = pipeline()
    val root = root()
    val file = Files.writeString(root.resolve("locked.txt"), "contents\n")
    Files.setPosixFilePermissions(file, emptySet())
    readable(registry, root)
    // Root ignores the mode bits; the test reports as skipped rather than quietly passing.
    assumeFalse(Files.isReadable(file), "this user can read a file at mode 000")

    val refused = assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "locked.txt")))
    assertIs<Failure.IoError>(refused.reason)
    assertContains(refused.message, "changed nothing")
  }
  @Test
  fun `an Operation is recorded once, bracketing the work, with the Origin its surface was wired with`() =
    runBlocking<Unit> {
      val (registry, activity, _, operations) = wiring(Origin.Frontend)
      val root = root()
      Files.writeString(root.resolve("notes.md"), "one\ntwo\n")
      readable(registry, root)

      operations.perform(Operation.ReadFile("api", "notes.md", offset = 2))

      val entry = activity.entries().single()
      assertEquals(Origin.Frontend, entry.origin)
      assertEquals("api", entry.workspace)
      assertEquals("read_file", entry.tool)
      assertEquals("path=notes.md offset=2", entry.arguments)
      assertEquals(activity.start, entry.runtimeStart)
      assertNotNull(entry.elapsed)
      assertEquals(ActivityOutcome.Ok("lines 2-2 of 2"), entry.outcome)
      assertFalse(entry.needsAttention)
    }

  @Test
  fun `the two surfaces are two views of one pipeline, and neither can claim the other's Origin`() =
    runBlocking<Unit> {
      val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
      val activity = Activity(temporary.resolve("activity"))
      val pipeline = WorkspaceOperationsPipeline(registry, activity)
      readable(registry, root())

      pipeline.operationsFor(Origin.ChatGpt).perform(Operation.ListWorkspaces)
      pipeline.operationsFor(Origin.Frontend).perform(Operation.ListWorkspaces)

      // `perform` takes no origin argument at all, so what is recorded is what the wiring said.
      assertEquals(listOf(Origin.ChatGpt, Origin.Frontend), activity.entries().map { it.origin })
    }

  @Test
  fun `a refused call is recorded as an ordinary failed entry and raises no flag`() = runBlocking<Unit> {
    val (registry, activity, _, operations) = wiring()
    val workspace = registry.perform(ManagementAct.Register(root().toString(), "api"))
    // Withheld, so the call is answered exactly as though no Workspace by that name existed.
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))

    assertIs<Outcome.Failed>(operations.perform(Operation.ReadFile("api", "notes.md")))

    val entry = activity.entries().single()
    assertEquals("api", entry.workspace, "the name the model kept trying is the point of the record")
    assertIs<ActivityOutcome.Failed>(entry.outcome)
    assertFalse(entry.needsAttention, "nothing about a refusal is unresolved")
    assertEquals(0, activity.unresolvedCount())
  }

  @Test
  fun `an Operation the Runtime was taken from reads back as Lost, and is not retried`() = runBlocking<Unit> {
    val (registry, activity, _, operations) = wiring()
    val root = root()
    Files.writeString(root.resolve("notes.md"), "one\n")
    readable(registry, root)
    operations.perform(Operation.ReadFile("api", "notes.md"))
    // What a Runtime killed between the two records leaves behind: the open, and no completion.
    val opened = Files.readString(temporary.resolve("activity")).lines().first()
    Files.writeString(temporary.resolve("activity"), opened + "\n")

    val restarted = Activity(temporary.resolve("activity"))
    val entry = restarted.entries().single()
    assertEquals(ActivityOutcome.Lost, entry.outcome)
    assertEquals(1, restarted.unresolvedCount())
    assertNotEquals(restarted.start, entry.runtimeStart)
  }

  @Test
  fun `Acknowledge is a ManagementAct, and settles what it names without touching it`() = runBlocking<Unit> {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    val management: WorkspaceManagement = RuntimeManagement(
      registry, activity, unstartedTunnel(temporary), WorkspaceOperationsPipeline(registry, activity),
      ConnectorAcknowledgement(temporary.resolve("connector")), RuntimeFeed(),
    )
    val id = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.complete(id, ActivityOutcome.Uncertain("It was stopped; effects uncertain, do not retry."))
    val before = Files.readString(temporary.resolve("activity"))

    management.perform(ManagementAct.Acknowledge(id))

    assertTrue(Files.readString(temporary.resolve("activity")).startsWith(before))
    assertNotNull(activity.entries().single().acknowledgedAt)
    assertEquals(0, activity.unresolvedCount())
  }

  @Test
  fun `run_command needs Command, and a cwd left out is the Root`() = runBlocking<Unit> {
    val (registry, _, _, operations) = commandWiring()
    val root = root()
    val workspace = commandable(registry, root)

    val result = finished(operations.perform(Operation.RunCommand("api", "pwd", deliveryKey = "one")))
    assertEquals("${root.toRealPath()}\n", result.output)

    // The dial governs the calls that arrive after it, and it governs this one.
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val refused = assertIs<Outcome.Failed>(
      operations.perform(Operation.RunCommand("api", "pwd", deliveryKey = "two")),
    )
    assertEquals(Failure.LevelTooLow("api", AccessLevel.Write, AccessLevel.Command), refused.reason)
  }

  @Test
  fun `a cwd outside the Root is refused, and what the command then reads is not confined`() = runBlocking<Unit> {
    // SPEC §2.4: the Root stops confining what a command *does*, not where the Runtime starts
    // it. Both halves of that sentence are load-bearing, so both are tested here.
    val (registry, _, _, operations) = commandWiring()
    val root = root()
    val outside = Files.writeString(temporary.resolve("secret.txt"), "not in the Root\n")
    commandable(registry, root)

    val refused = assertIs<Outcome.Failed>(
      operations.perform(Operation.RunCommand("api", "pwd", cwd = "..", deliveryKey = "one")),
    )
    assertEquals(Failure.OutsideRoot("api", ".."), refused.reason)

    val reached = finished(operations.perform(Operation.RunCommand("api", "cat '$outside'", deliveryKey = "two")))
    assertEquals("not in the Root\n", reached.output)
  }

  @Test
  fun `a cwd inside the Root is where the command runs, and is what the reply says`() = runBlocking<Unit> {
    val (registry, _, _, operations) = commandWiring()
    val root = root()
    val sub = Files.createDirectory(root.resolve("sub"))
    commandable(registry, root)

    val result = finished(operations.perform(Operation.RunCommand("api", "pwd", cwd = "sub", deliveryKey = "one")))
    assertEquals("${sub.toRealPath()}\n", result.output)
    assertEquals("sub", result.cwd)
  }

  @Test
  fun `lowering a level while a command runs stops nothing, and the next call is refused`() = runBlocking<Unit> {
    // SPEC §13.2 scenario 2. Revocation is not a stop button: a level change governs the calls
    // that arrive after it and does not reach into work already running.
    val (registry, _, _, operations) = commandWiring()
    val root = root()
    val workspace = commandable(registry, root)
    val go = root.resolve("go")

    val running = async(Dispatchers.IO) {
      operations.perform(
        Operation.RunCommand(
          "api", "touch running; until [ -f '$go' ]; do sleep 0.05; done; touch finished", deliveryKey = "one",
        ),
      )
    }
    try {
      awaitFile(root.resolve("running"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))

      val refused = assertIs<Outcome.Failed>(
        operations.perform(Operation.RunCommand("api", "true", deliveryKey = "two")),
      )
      assertEquals(Failure.LevelTooLow("api", AccessLevel.Read, AccessLevel.Command), refused.reason)
      assertTrue(Files.notExists(root.resolve("finished")), "the running command has not been stopped")
    } finally {
      Files.writeString(go, "")
    }

    assertEquals(0, finished(running.await()).exitCode)
    assertTrue(Files.exists(root.resolve("finished")), "the command ran to completion across the change")
  }

  @Test
  fun `a command is recorded whole, with its key and everything it said`() = runBlocking<Unit> {
    // The account is what an unattended Command Workspace is reviewed by, and a command
    // summarised into its first few words is not something anybody can review.
    val (registry, activity, _, operations) = commandWiring()
    commandable(registry, root())

    operations.perform(Operation.RunCommand("api", "echo done", cwd = null, deliveryKey = "abc"))

    val entry = activity.entries().single()
    assertEquals("run_command", entry.tool)
    assertEquals("command=echo done cwd=. request_id=abc", entry.arguments)
    val recorded = assertIs<ActivityOutcome.Ok>(entry.outcome)
    assertContains(recorded.detail, "exit 0")
    assertContains(recorded.detail, "done")
  }

  @Test
  fun `a command past the budget is Promoted, and the Handle arrives before the outcome exists`() =
    runBlocking<Unit> {
      // SPEC §13.2 scenario 7, and ADR 0003's whole reason: killing a command mid-flight
      // manufactures Uncertain, and letting it finish produces something known.
      val (registry, activity, pipeline, operations) = commandWiring(budget = 300.milliseconds)
      val root = root()
      val workspace = commandable(registry, root)
      val go = root.resolve("go")

      val promoted = assertIs<CommandReply.Promoted>(
        assertIs<Outcome.Ok<CommandReply>>(
          operations.perform(
            Operation.RunCommand(
              "api",
              "printf 'first\n'; until [ -f '$go' ]; do sleep 0.05; done; printf 'last\n'",
              deliveryKey = "one",
            ),
          ),
        ).value,
      )

      // The reply precedes the outcome: the entry is open, and nothing has been decided.
      assertEquals(ActivityOutcome.InFlight, activity.entries().single().outcome)
      assertContains(promoted.running.outputSoFar, "first")

      // Promoted again, with the output captured so far.
      val running = assertIs<Collected.StillRunning>(collected(operations, promoted.handle))
      assertContains(running.running.outputSoFar, "first")
      assertFalse("last" in running.running.outputSoFar)

      Files.writeString(go, "")
      // Then the finished Operation, with its outcome.
      val reached = await("the command to finish") {
        collected(operations, promoted.handle) as? Collected.Reached
      }
      val result = assertIs<Outcome.Ok<CommandResult>>(reached.outcome)
      assertEquals(0, result.value.exitCode)
      assertContains(result.value.output, "last")

      // The account: it ran unattended and somebody has now heard what it did, which is what
      // settles an Unclaimed entry (§10.1).
      val entry = activity.entries().first { it.tool == "run_command" }
      assertIs<ActivityOutcome.Ok>(entry.outcome)
      assertFalse(entry.needsAttention, "a collected result is settled")

      // And the collecting route closes when the dial does (§4, §6.2).
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
      val refused = assertIs<Outcome.Failed>(
        operations.perform(Operation.GetResult("api", promoted.handle.value)),
      )
      assertEquals(Failure.LevelTooLow("api", AccessLevel.Read, AccessLevel.Command), refused.reason)
      pipeline.stop()
    }

  @Test
  fun `a running command is watched from the stream - promoted, its buffer the one get_result reads, and listed until reaped`() =
    runBlocking<Unit> {
      // SPEC §10.2: the band is drawn from what a frontend is told, so each of these has to be
      // on the stream rather than inferred by the frontend.
      val feed = RuntimeFeed()
      val (registry, activity, pipeline, operations) = commandWiring(budget = 200.milliseconds, feed = feed)
      val management: WorkspaceManagement = RuntimeManagement(
        registry, activity, unstartedTunnel(temporary), pipeline, ConnectorAcknowledgement(temporary.resolve("connector")), feed,
      )
      commandable(registry, root())
      var state: RuntimeEvent.Snapshot? = null
      val phases = mutableListOf<StopPhase?>()
      val watching = launch(Dispatchers.IO) {
        management.observe().collect { event ->
          val next = when (event) {
            is RuntimeEvent.Snapshot -> event
            is RuntimeEvent.Change -> state!!.after(event)
          }
          next.running.singleOrNull()?.stopping?.let { if (phases.lastOrNull() != it) phases += it }
          state = next
        }
      }

      val promoted = assertIs<CommandReply.Promoted>(
        assertIs<Outcome.Ok<CommandReply>>(
          // It ignores TERM, so it is still running when the grace is over and the KILL is what ends it.
          operations.perform(Operation.RunCommand("api", "trap '' TERM; printf 'first\\n'; sleep 30", deliveryKey = "k")),
        ).value,
      )
      val entry = promoted.handle.entry
      await("the promotion on the stream") { state?.running?.singleOrNull()?.takeIf { it.promoted } }
      assertTrue(state!!.catalog.map { it.name }.containsAll(listOf("run_command", "get_result")), "the catalog rides the snapshot")

      val read = assertNotNull(management.perform(ManagementAct.ReadOutput(entry)))
      val still = assertIs<Collected.StillRunning>(collected(operations, promoted.handle))
      assertEquals(still.running.outputSoFar, read.outputSoFar, "the screen and the tool read one buffer")
      assertEquals("first\n", read.outputSoFar)

      val stopping = async(Dispatchers.IO) { management.perform(ManagementAct.StopOperation(entry)) }
      await("the stop to begin") { state?.running?.singleOrNull()?.stopping }
      assertEquals(entry, state!!.running.single().entry, "a stopping command is still listed")
      // There is no second, harder stop: asking again ends nothing sooner and signals nothing twice.
      val again = measure { management.perform(ManagementAct.StopOperation(entry)) }
      assertTrue(again < 400.milliseconds, "a second stop waited $again")
      stopping.await()

      await("the end on the stream") { state?.takeIf { it.running.isEmpty() } }
      assertEquals<List<StopPhase?>>(listOf(StopPhase.Terminating, StopPhase.Killing), phases)
      assertNull(management.perform(ManagementAct.ReadOutput(entry)), "nothing is running as that entry now")
      assertIs<ActivityOutcome.Uncertain>(activity.entry(entry)!!.outcome)
      watching.cancelAndJoin()
      pipeline.stop()
    }

  @Test
  fun `a promoted command nobody collects reads Unclaimed, and needs somebody's attention`() =
    runBlocking<Unit> {
      val (registry, activity, pipeline, operations) = commandWiring(budget = 200.milliseconds)
      commandable(registry, root())

      val promoted = assertIs<CommandReply.Promoted>(
        assertIs<Outcome.Ok<CommandReply>>(
          operations.perform(Operation.RunCommand("api", "sleep 0.5; echo done", deliveryKey = "one")),
        ).value,
      )

      val entry = await("the promoted command to finish") {
        activity.entries().single { it.tool == "run_command" }.takeIf { it.outcome is ActivityOutcome.Unclaimed }
      }
      // Not Undelivered — the reply *was* delivered. Not Uncertain — the outcome is known. Not
      // Lost — there is a result. Nobody has simply heard it.
      val unclaimed = assertIs<ActivityOutcome.Unclaimed>(entry.outcome)
      assertIs<ActivityOutcome.Ok>(unclaimed.completed)
      assertTrue(entry.needsAttention)

      assertIs<Collected.Reached>(collected(operations, promoted.handle))
      assertFalse(
        activity.entries().single { it.tool == "run_command" }.needsAttention,
        "a later get_result carries it through",
      )
      pipeline.stop()
    }

  @Test
  fun `a handle presented with the wrong Workspace is refused, and so is one naming nothing`() =
    runBlocking<Unit> {
      val (registry, _, pipeline, operations) = commandWiring(budget = 200.milliseconds)
      commandable(registry, root("one"), "api")
      commandable(registry, root("two"), "other")

      val promoted = assertIs<CommandReply.Promoted>(
        assertIs<Outcome.Ok<CommandReply>>(
          operations.perform(Operation.RunCommand("api", "sleep 5", deliveryKey = "one")),
        ).value,
      )

      val elsewhere = assertIs<Outcome.Failed>(
        operations.perform(Operation.GetResult("other", promoted.handle.value)),
      )
      assertEquals(Failure.HandleNotInWorkspace(promoted.handle.value, "other"), elsewhere.reason)

      val nothing = assertIs<Outcome.Failed>(operations.perform(Operation.GetResult("api", "not-a-handle")))
      assertEquals(Failure.NoSuchHandle("not-a-handle"), nothing.reason)
      pipeline.stop()
    }

  @Test
  fun `a promoted command holds one of the four slots until it is stopped`() = runBlocking<Unit> {
    // SPEC §6.3: there is no maximum lifetime for a promoted command, so the cap is the runaway
    // guard and a promoted command counts against it.
    val (registry, _, pipeline, operations) = commandWiring(budget = 200.milliseconds)
    commandable(registry, root())

    repeat(Operation.COMMAND_CONCURRENCY_CAP) { index ->
      assertIs<CommandReply.Promoted>(
        assertIs<Outcome.Ok<CommandReply>>(
          operations.perform(Operation.RunCommand("api", "sleep 30", deliveryKey = "k$index")),
        ).value,
      )
    }

    val refused = assertIs<Outcome.Failed>(
      operations.perform(Operation.RunCommand("api", "true", deliveryKey = "over")),
    )
    assertEquals(Failure.CommandCapReached(Operation.COMMAND_CONCURRENCY_CAP), refused.reason)
    pipeline.stop()
  }

  @Test
  fun `a discarded answer leaves the command running, its result kept, and the entry Undelivered`() =
    runBlocking<Unit> {
      // SPEC §6.2: work is never abandoned. Abandoning it mid-flight is exactly what
      // manufactures Uncertain, and `failed`'s "nothing changed" is bought by not doing that.
      val (registry, activity, pipeline, operations) = commandWiring()
      val root = root()
      commandable(registry, root)

      val running = launch(Dispatchers.IO) {
        operations.perform(
          Operation.RunCommand("api", "touch running; sleep 0.5; touch finished", deliveryKey = "one"),
        )
      }
      awaitFile(root.resolve("running"))
      running.cancelAndJoin()

      val entry = await("the discarded command to finish") {
        activity.entries().single { it.tool == "run_command" }.takeIf { it.outcome is ActivityOutcome.Undelivered }
      }
      val undelivered = assertIs<ActivityOutcome.Undelivered>(entry.outcome)
      assertIs<ActivityOutcome.Ok>(undelivered.completed)
      assertTrue(
        Files.exists(root.resolve("finished")),
        "the Operation ran to completion although nobody was left to hear it",
      )
      pipeline.stop()
    }

  @Test
  fun `Runtime Stop leaves a promoted command Uncertain rather than Lost, and refuses new calls`() =
    runBlocking<Unit> {
      // SPEC §13.2 scenario 11. Lost is reserved for a Runtime taken from the machine; this one
      // chose to end the work and knows that it did.
      val (registry, activity, pipeline, operations) = commandWiring(budget = 200.milliseconds)
      commandable(registry, root())

      repeat(Operation.COMMAND_CONCURRENCY_CAP) { index ->
        assertIs<Outcome.Ok<CommandReply>>(
          operations.perform(Operation.RunCommand("api", "sleep 30", deliveryKey = "k$index")),
        )
      }

      val took = measure { pipeline.stop() }

      val entries = activity.entries().filter { it.tool == "run_command" }
      assertEquals(Operation.COMMAND_CONCURRENCY_CAP, entries.size)
      entries.forEach { entry ->
        val uncertain = assertIs<ActivityOutcome.Uncertain>(entry.outcome)
        assertContains(uncertain.detail, Outcome.Uncertain.EFFECTS_UNCERTAIN)
        assertTrue(entry.needsAttention, "the Runtime ended this and somebody has to be told")
      }
      // One grace period in total, not one per command.
      assertTrue(took < Operation.KILL_GRACE, "Stop took $took for ${entries.size} commands")

      val refused = assertIs<Outcome.Failed>(
        operations.perform(Operation.RunCommand("api", "true", deliveryKey = "after")),
      )
      assertEquals(Failure.RuntimeStopping, refused.reason)
    }

  @Test
  fun `one promoted command failing does not cancel another, and neither is cancelled by its caller`() =
    runBlocking<Unit> {
      // §6.6: the scope promoted work runs in carries a SupervisorJob and outlives the request
      // coroutine that started it. Both halves matter — a build must not be taken down by an
      // unrelated command, nor by the call that asked for it having gone.
      val (registry, activity, pipeline, operations) = commandWiring(budget = 200.milliseconds)
      val root = root()
      commandable(registry, root)

      val doomed = promoted(operations, "sleep 0.4; exit 9", "one")
      val healthy = promoted(operations, "sleep 0.8; touch survived; echo fine", "two")

      val reached = await("the healthy command to finish") {
        collected(operations, healthy.handle) as? Collected.Reached
      }
      val result = assertIs<Outcome.Ok<CommandResult>>(reached.outcome)
      assertEquals(0, result.value.exitCode)
      assertTrue(Files.exists(root.resolve("survived")))

      // The one that exited 9 reached an outcome of its own, and a non-zero exit is an `ok`.
      val other = assertIs<Collected.Reached>(collected(operations, doomed.handle))
      assertEquals(9, assertIs<Outcome.Ok<CommandResult>>(other.outcome).value.exitCode)
      assertEquals(2, activity.entries().count { it.tool == "run_command" })
      pipeline.stop()
    }

  @Test
  fun `collecting twice collects the same thing, and the account still says one Delivery`() =
    runBlocking<Unit> {
      // §4: `get_result` is idempotent, which is why it carries no Delivery key. An entry that
      // read "4 deliveries" for a command nobody delivered twice would have the account
      // inventing the transport's behaviour rather than revealing it.
      val (registry, activity, pipeline, operations) = commandWiring(budget = 200.milliseconds)
      commandable(registry, root())
      val handle = promoted(operations, "sleep 0.4; echo once", "one").handle

      val first = await("the command to finish") { collected(operations, handle) as? Collected.Reached }
      val second = assertIs<Collected.Reached>(collected(operations, handle))

      assertEquals(
        assertIs<Outcome.Ok<CommandResult>>(first.outcome).value,
        assertIs<Outcome.Ok<CommandResult>>(second.outcome).value,
      )
      assertEquals(1, activity.entries().single { it.tool == "run_command" }.deliveries)
      pipeline.stop()
    }

  @Test
  fun `a Handle whose Runtime is gone resolves to the Lost Operation it points at`() = runBlocking<Unit> {
    // §6.2: the Handle names an entry in Activity rather than a record of its own, so this
    // needs nothing arranging — an entry a previous Runtime opened and never closed reads Lost.
    val account = temporary.resolve("activity")
    val taken = Activity(account)
    val handle = taken.open(Origin.ChatGpt, "api", "run_command", "command=make cwd=. request_id=one")

    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val restarted = Activity(account)
    val operations = WorkspaceOperationsPipeline(registry, restarted).operationsFor(Origin.ChatGpt)
    commandable(registry, root())

    val recorded = assertIs<Collected.Recorded>(collected(operations, Handle(handle)))
    assertEquals(ActivityOutcome.Lost, recorded.entry.outcome)
    // Lost carries nothing through, so it stays unresolved: there is no result to hear, and
    // acknowledgement is the one route left.
    assertTrue(restarted.entries().single { it.tool == "run_command" }.needsAttention)
  }

  @Test
  fun `a search that outruns its budget is ok with its time marker, and carries no Handle`() =
    runBlocking<Unit> {
      // SPEC §6.2: only `run_command` promotes. Search is time-bounded instead, and a search
      // that found less is a result rather than an Uncertain.
      val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
      val activity = Activity(temporary.resolve("activity"))
      val operations = WorkspaceOperationsPipeline(registry, activity, searchBudget = Duration.ZERO)
        .operationsFor(Origin.ChatGpt)
      val root = root()
      Files.writeString(root.resolve("a.txt"), "needle\n")
      readable(registry, root)

      val found = assertIs<Outcome.Ok<SearchResults>>(operations.perform(Operation.Search("api", "needle")))
      assertTrue(found.value.bounds.cappedByTime)
      assertIs<ActivityOutcome.Ok>(activity.entries().single().outcome)
    }

  /** A `run_command` the budget promoted, which is what a Handle comes from. */
  private suspend fun promoted(
    operations: WorkspaceOperations,
    command: String,
    key: String,
  ): CommandReply.Promoted = assertIs<CommandReply.Promoted>(
    assertIs<Outcome.Ok<CommandReply>>(
      operations.perform(Operation.RunCommand("api", command, deliveryKey = key)),
    ).value,
  )

  /** One `get_result`, which is `ok` however the command it collected turned out. */
  private suspend fun collected(operations: WorkspaceOperations, handle: Handle): Collected =
    assertIs<Outcome.Ok<Collected>>(operations.perform(Operation.GetResult("api", handle.value))).value

  /** Polls until [what] yields something, which is how a promoted command is waited on. */
  private suspend fun <T : Any> await(reason: String, what: suspend () -> T?): T {
    val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
      what()?.let { return it }
      delay(20)
    }
    fail("Waited 30s for $reason")
  }

  private suspend fun measure(what: suspend () -> Unit): Duration {
    val started = System.nanoTime()
    what()
    return (System.nanoTime() - started).nanoseconds
  }

  private fun awaitFile(file: Path) {
    val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
      if (Files.exists(file)) return
      Thread.sleep(20)
    }
    fail("Waited 30s for $file")
  }
}
