package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * SPEC §4 and §6.6: the two mutating file tools, what they refuse, what they preserve, and the
 * lock they serialize on. Everything here goes through the pipeline rather than the functions
 * behind it, because confinement, the Access Level and the lock are steps of the pipeline and
 * a test that skipped them would prove the wrong thing.
 */
class FileWritesTest {
  @TempDir
  lateinit var temporary: Path

  private data class Wiring(
    val registry: WorkspaceRegistry,
    val activity: Activity,
    val operations: WorkspaceOperations,
    /** The pipeline's own mutation lock, so a test can hold it and ask what a call does then. */
    val locks: PathLocks,
  )

  private fun wiring(): Wiring {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    val locks = PathLocks()
    val pipeline = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, locks)
    return Wiring(registry, activity, pipeline.operationsFor(Origin.ChatGpt), locks)
  }

  private fun root(name: String = "project"): Path = Files.createDirectory(temporary.resolve(name))

  private suspend fun writable(registry: WorkspaceRegistry, root: Path, name: String = "api") {
    val workspace = registry.perform(ManagementAct.Register(root.toString(), name))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
  }

  /** One key per call unless a test says otherwise: a reused key is a repeat Delivery (§6.4). */
  private fun freshKey(): String = java.util.UUID.randomUUID().toString()

  private fun write(path: String, content: String, key: String = freshKey()) =
    Operation.WriteFile("api", path, content, key)

  private fun edit(path: String, old: String, new: String, key: String = freshKey()) =
    Operation.EditFile("api", path, old, new, key)

  @Test
  fun `write_file creates a file that was not there and replaces one that was`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    writable(registry, root)

    val created = assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("notes.md", "one\n"))).value
    assertEquals(FileWritten("notes.md", 4, created = true), created)
    assertEquals("one\n", Files.readString(root.resolve("notes.md")))

    val replaced = assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("notes.md", "two\nthree\n"))).value
    assertEquals(FileWritten("notes.md", 10, created = false), replaced)
    assertEquals("two\nthree\n", Files.readString(root.resolve("notes.md")))
  }

  @Test
  fun `write_file does not create missing parent directories, and creates nothing at all`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    writable(registry, root)

    val refused = assertIs<Outcome.Failed>(operations.perform(write("does/not/exist/notes.md", "one\n")))
    assertEquals(Failure.NotADirectory("api", "does/not/exist"), refused.reason)
    assertContains(refused.message, "does/not/exist")
    assertContains(refused.message, "changed nothing")
    assertEquals(emptyList(), Files.list(root).use { it.toList() }, "a refused write grew a tree")
  }

  @Test
  fun `write_file refuses a path that is a directory rather than replacing it`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    Files.createDirectory(root.resolve("sub"))
    writable(registry, root)

    val refused = assertIs<Outcome.Failed>(operations.perform(write("sub", "one\n")))
    assertEquals(Failure.NotAFile("api", "sub"), refused.reason)
    assertTrue(Files.isDirectory(root.resolve("sub")))
  }

  @Test
  fun `a write preserves permission bits, CRLF endings and the trailing-newline convention`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val executable = Files.writeString(root.resolve("run.sh"), "old\n")
    val mode = PosixFilePermissions.fromString("rwxr-x---")
    Files.setPosixFilePermissions(executable, mode)
    Files.write(root.resolve("windows.txt"), "one\r\ntwo\r\n".toByteArray())
    Files.write(root.resolve("bare.txt"), "one\ntwo".toByteArray())
    writable(registry, root)

    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("run.sh", "new\n")))
    assertEquals(mode, Files.getPosixFilePermissions(executable))
    assertEquals("new\n", Files.readString(executable))

    // The file speaks CRLF; content handed over with bare newlines lands in the file's own
    // convention rather than turning half of it into something `git diff` calls a rewrite.
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("windows.txt", "three\nfour\n")))
    assertEquals("three\r\nfour\r\n", String(Files.readAllBytes(root.resolve("windows.txt"))))

    // No trailing newline before, none after — and the byte count reports what landed.
    val bare = assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("bare.txt", "three\nfour\n"))).value
    assertEquals("three\nfour", String(Files.readAllBytes(root.resolve("bare.txt"))))
    assertEquals(10, bare.bytes)

    // A file that had one keeps one, whatever the content says.
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("run.sh", "newer")))
    assertEquals("newer\n", Files.readString(executable))
  }

  @Test
  fun `a new file is readable, not locked down to the temp file's own mode`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    writable(registry, root)

    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("fresh.txt", "one\n")))
    val permissions = Files.getPosixFilePermissions(root.resolve("fresh.txt"))
    assertContains(permissions, PosixFilePermission.OWNER_READ)
    assertContains(permissions, PosixFilePermission.OWNER_WRITE)
    // What the account's umask would have given an ordinary `touch`, not the 0600 a temp file
    // is created with: a file ChatGPT wrote is a file the user's own tools can read.
    val touched = Files.createFile(root.resolve("touched.txt"))
    assertEquals(Files.getPosixFilePermissions(touched), permissions)
  }

  @Test
  fun `both tools land by temp file and atomic rename, leaving no temp file behind`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "one\n")
    // The consequence on the record: rename replaces the inode, so a hardlink is left holding
    // what the file used to be rather than following it.
    val hardlink = Files.createLink(root.resolve("hard.md"), file)
    writable(registry, root)

    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("notes.md", "two\n")))
    assertEquals("two\n", Files.readString(file))
    assertEquals("one\n", Files.readString(hardlink), "the rename should have replaced the inode")

    assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit("notes.md", "two", "three")))
    assertEquals("three\n", Files.readString(file))
    assertEquals("one\n", Files.readString(hardlink))

    assertEquals(
      listOf("hard.md", "notes.md"),
      Files.list(root).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() },
    )
  }

  @Test
  fun `edit_file applies the one match it found`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    Files.writeString(root.resolve("notes.md"), "alpha\nbeta\ngamma\n")
    writable(registry, root)

    val edited = assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit("notes.md", "beta", "delta"))).value
    assertEquals(FileWritten("notes.md", 18, created = false), edited)
    assertEquals("alpha\ndelta\ngamma\n", Files.readString(root.resolve("notes.md")))
  }

  @Test
  fun `edit_file refuses zero matches and several, naming the count, and changes nothing`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "alpha\nbeta\nalpha\n")
    writable(registry, root)

    val absent = assertIs<Outcome.Failed>(operations.perform(edit("notes.md", "gamma", "delta")))
    assertEquals(Failure.NoMatch("api", "notes.md"), absent.reason)
    assertContains(absent.message, "changed nothing")
    assertEquals("alpha\nbeta\nalpha\n", Files.readString(file))

    val several = assertIs<Outcome.Failed>(operations.perform(edit("notes.md", "alpha", "delta")))
    assertEquals(Failure.SeveralMatches("api", "notes.md", 2), several.reason)
    assertContains(several.message, "2")
    assertContains(several.message, "changed nothing")
    assertEquals("alpha\nbeta\nalpha\n", Files.readString(file), "never first-one-wins")
  }

  @Test
  fun `edit_file matches byte for byte, including a match that spans lines`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    Files.writeString(root.resolve("notes.md"), "alpha\n  beta\nAlpha\n")
    writable(registry, root)

    // Case is part of the match, and so is whitespace; nothing here is normalised away.
    assertEquals(
      Failure.NoMatch("api", "notes.md"),
      assertIs<Outcome.Failed>(operations.perform(edit("notes.md", "Beta", "x"))).reason,
    )
    assertEquals(
      Failure.NoMatch("api", "notes.md"),
      assertIs<Outcome.Failed>(operations.perform(edit("notes.md", "beta Alpha", "x"))).reason,
    )
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit("notes.md", "  beta\nAlpha", "  gamma\nOmega")))
    assertEquals("alpha\n  gamma\nOmega\n", Files.readString(root.resolve("notes.md")))
  }

  @Test
  fun `an edit of a CRLF file keeps its endings and its permission bits`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val file = Files.write(root.resolve("windows.txt"), "one\r\ntwo\r\nthree\r\n".toByteArray())
    val mode = PosixFilePermissions.fromString("rw-r--r--")
    Files.setPosixFilePermissions(file, mode)
    writable(registry, root)

    // The text the model quotes back from `read_file` carries the CRs, and so it matches.
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit("windows.txt", "two\r\n", "four\r\nfive\r\n")))
    assertEquals("one\r\nfour\r\nfive\r\nthree\r\n", String(Files.readAllBytes(file)))
    assertEquals(mode, Files.getPosixFilePermissions(file))

    // Quoted with bare newlines it does not match: the match is byte for byte (§4), and a
    // near-enough edit is not one. Nothing changed, so the model can read and quote again.
    val bare = assertIs<Outcome.Failed>(operations.perform(edit("windows.txt", "four\nfive\n", "six\n")))
    assertEquals(Failure.NoMatch("api", "windows.txt"), bare.reason)
    assertEquals("one\r\nfour\r\nfive\r\nthree\r\n", String(Files.readAllBytes(file)))

    // What the file's convention does govern is the replacement: an edit quoted byte-exactly
    // does not leave a bare newline behind in a file that speaks CRLF.
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit("windows.txt", "four\r\nfive\r\n", "six\n")))
    assertEquals("one\r\nsix\r\nthree\r\n", String(Files.readAllBytes(file)))
  }

  @Test
  fun `edit_file refuses a missing file, a binary one and an empty old_text`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    Files.write(root.resolve("image.png"), byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x0D))
    Files.writeString(root.resolve("notes.md"), "alpha\n")
    writable(registry, root)

    assertEquals(
      Failure.NotAFile("api", "missing.md"),
      assertIs<Outcome.Failed>(operations.perform(edit("missing.md", "a", "b"))).reason,
    )
    assertEquals(
      Failure.Binary("api", "image.png"),
      assertIs<Outcome.Failed>(operations.perform(edit("image.png", "a", "b"))).reason,
    )
    val empty = assertIs<Outcome.Failed>(operations.perform(edit("notes.md", "", "b")))
    assertEquals(Failure.InvalidArgument("old_text"), empty.reason)
    assertEquals("alpha\n", Files.readString(root.resolve("notes.md")))
  }

  @Test
  fun `a mutation is refused below Write and confined to the Root like any other path`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val outside = Files.writeString(temporary.resolve("secret.txt"), "not yours\n")
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))

    val tooLow = assertIs<Outcome.Failed>(operations.perform(write("notes.md", "one\n")))
    assertEquals(Failure.LevelTooLow("api", AccessLevel.Read, AccessLevel.Write), tooLow.reason)
    assertFalse(Files.exists(root.resolve("notes.md")))

    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val escaping = assertIs<Outcome.Failed>(operations.perform(write("../secret.txt", "mine now\n")))
    assertEquals(Failure.OutsideRoot("api", "../secret.txt"), escaping.reason)
    assertEquals("not yours\n", Files.readString(outside))
  }

  @Test
  fun `request_id is required by both schemas, is rejected when blank, and is recorded`() = runBlocking<Unit> {
    val (registry, activity, operations) = wiring()
    val root = root()
    Files.writeString(root.resolve("notes.md"), "alpha\n")
    writable(registry, root)

    // The schemas: a required key, and nothing resembling a precondition token beside it.
    for (name in listOf("write_file", "edit_file")) {
      val entry = operations.catalog.single { it.name == name }
      val key = entry.arguments.single { it.name == "request_id" }
      assertTrue(key.required, "$name must require its Delivery key")
      assertTrue(
        entry.arguments.none { it.name in setOf("mtime", "hash", "sha", "expected_hash", "precondition", "version") },
        "$name carries a precondition token: ${entry.arguments.map { it.name }}",
      )
    }
    // One edit per call: the arguments are one old_text and one new_text, never a list of them.
    assertEquals(
      listOf("workspace", "path", "old_text", "new_text", "request_id"),
      operations.catalog.single { it.name == "edit_file" }.arguments.map { it.name },
    )
    assertEquals(
      listOf("workspace", "path", "content", "request_id"),
      operations.catalog.single { it.name == "write_file" }.arguments.map { it.name },
    )

    val missing = assertIs<Outcome.Failed>(operations.perform(write("notes.md", "one\n", key = " ")))
    assertEquals(Failure.MissingKey, missing.reason)
    assertEquals("alpha\n", Files.readString(root.resolve("notes.md")))

    assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit("notes.md", "alpha", "beta", key = "abc-123")))
    val recorded = activity.entries().last()
    assertEquals("edit_file", recorded.tool)
    assertContains(recorded.arguments, "request_id=abc-123")
  }

  /** Scenario 12 of SPEC §13.2. */
  @Test
  fun `a mutation in flight when the Runtime dies reads back as Lost, and the entry survives`() = runBlocking<Unit> {
    val (registry, _, operations, locks) = wiring()
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "alpha\n")
    writable(registry, root)
    val account = temporary.resolve("activity")

    // Holding the lock the pipeline takes leaves the mutation genuinely in flight: its entry
    // is open — written before the work begins — and no completion has been written.
    val holding = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val holder = launch(Dispatchers.Default) {
      locks.withPathLock(file.toRealPath().toString()) {
        holding.complete(Unit)
        release.await()
      }
    }
    holding.await()
    val inFlight = async(Dispatchers.Default) { operations.perform(edit("notes.md", "alpha", "beta", key = "k9")) }
    // Wait for the record rather than sample for it: the open is written before the work, so
    // its absence now would otherwise read as a pass.
    fun records(): List<String> = if (Files.exists(account)) Files.readAllLines(account) else emptyList()
    while (records().none { it.startsWith("open") }) delay(10)
    assertTrue(records().none { it.startsWith("complete") }, "the work has not finished")

    // What a later Start reads of an entry its Runtime never closed. The file is the account;
    // nothing here was rewritten to arrange it.
    val restarted = Activity(account)
    val entry = restarted.entries().single()
    assertEquals(ActivityOutcome.Lost, entry.outcome)
    assertEquals("edit_file", entry.tool)
    assertContains(entry.arguments, "request_id=k9")
    assertEquals(1, restarted.unresolvedCount())
    assertNotEquals(restarted.start, entry.runtimeStart)
    // And it survives a further restart to say so: the account is where it lives, not memory.
    assertEquals(ActivityOutcome.Lost, Activity(account).entries().single().outcome)

    release.complete(Unit)
    holder.join()
    assertIs<Outcome.Ok<FileWritten>>(inFlight.await())
  }

  @Test
  fun `a write that cannot be staged beside its target changes nothing and says so`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val closed = Files.createDirectory(root.resolve("closed"))
    val file = Files.writeString(closed.resolve("notes.md"), "alpha\n")
    writable(registry, root)
    // The temp file is created in the target's own directory, so a directory nothing may be
    // created in stops the write — even though the file itself is perfectly writable.
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("r-x------"))
    try {
      // Root ignores the mode bits; the test reports as skipped rather than quietly passing.
      assumeFalse(Files.isWritable(closed), "this user can create files in a directory at mode 500")

      val refused = assertIs<Outcome.Failed>(operations.perform(write("closed/notes.md", "beta\n")))
      assertIs<Failure.IoError>(refused.reason)
      assertContains(refused.message, "changed nothing")
      assertEquals("alpha\n", Files.readString(file), "the target was left exactly as it was")
    } finally {
      Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"))
    }
  }

  @Test
  fun `a trailing newline is kept or dropped one line ending at a time`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    Files.write(root.resolve("bare.txt"), "one\ntwo".toByteArray())
    writable(registry, root)

    // The file ends without one, so what lands ends without one — and the blank line the
    // caller asked for before it is content, not convention.
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write("bare.txt", "three\n\n")))
    assertEquals("three\n", String(Files.readAllBytes(root.resolve("bare.txt"))))
  }

  @Test
  fun `overlapping occurrences are several matches, not one`() = runBlocking<Unit> {
    val (registry, _, operations) = wiring()
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "aaa\n")
    writable(registry, root)

    // 'aa' sits at two places in 'aaa'. Counting them as one would be first-one-wins by
    // arithmetic, which is the one thing §4 rules out.
    val several = assertIs<Outcome.Failed>(operations.perform(edit("notes.md", "aa", "b")))
    assertEquals(Failure.SeveralMatches("api", "notes.md", 2), several.reason)
    assertEquals("aaa\n", Files.readString(file))
  }

  /** Scenario 3 of SPEC §13.2. */
  @Test
  fun `two edits on one file through two overlapping Roots serialize on the resolved real path`() =
    runBlocking<Unit> {
      val (registry, _, operations) = wiring()
      val outer = root()
      val inner = Files.createDirectory(outer.resolve("inner"))
      val file = inner.resolve("notes.md")
      val slots = 24
      Files.writeString(file, (1..slots).joinToString("\n", postfix = "\n") { "slot $it: untouched" })
      writable(registry, outer, "api")
      registry.perform(ManagementAct.Register(inner.toString(), "nested"))
        .let { registry.perform(ManagementAct.SetLevel(it.id, AccessLevel.Write)) }

      // Every edit reads the whole file and writes the whole file back. Serialized on the real
      // path, all of them land; keyed on the Workspace, the two Roots would not contend and
      // edits would be lost under each other's rewrites.
      val edits = (1..slots).map { slot ->
        val throughOuter = slot % 2 == 0
        async(Dispatchers.Default) {
          operations.perform(
            Operation.EditFile(
              workspace = if (throughOuter) "api" else "nested",
              path = if (throughOuter) "inner/notes.md" else "notes.md",
              oldText = "slot $slot: untouched",
              newText = "slot $slot: edited",
              deliveryKey = "slot-$slot",
            ),
          )
        }
      }
      for (outcome in edits.awaitAll()) assertIs<Outcome.Ok<FileWritten>>(outcome)

      assertEquals(
        (1..slots).joinToString("\n", postfix = "\n") { "slot $it: edited" },
        Files.readString(file),
        "an edit was lost, so the two Roots did not contend on one lock",
      )
    }

  @Test
  fun `a read of a file a mutation holds the lock on is not itself held up`() = runBlocking<Unit> {
    val (registry, _, operations, locks) = wiring()
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "alpha\n")
    writable(registry, root)

    val holding = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val holder = launch(Dispatchers.Default) {
      // The pipeline's own lock, taken on the key the pipeline would take it on.
      locks.withPathLock(file.toRealPath().toString()) {
        holding.complete(Unit)
        release.await()
      }
    }
    holding.await()

    val blocked = async(Dispatchers.Default) { operations.perform(write("notes.md", "beta\n")) }
    // The read takes no lock, so it answers while the mutation is still waiting for one.
    val read = withTimeout(5_000) { operations.perform(Operation.ReadFile("api", "notes.md")) }
    assertEquals("alpha\n", assertIs<Outcome.Ok<FileContent>>(read).value.text)
    assertNull(withTimeoutOrNull(200) { blocked.await() }, "the mutation should still be waiting")

    release.complete(Unit)
    holder.join()
    assertIs<Outcome.Ok<FileWritten>>(blocked.await())
    assertEquals("beta\n", Files.readString(file))
    assertEquals(emptySet(), locks.heldKeys())
  }
}
