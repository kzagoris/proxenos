package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * SPEC §6.4 and scenario 8 of §13.2: the same `request_id` arriving again. The transport mints
 * the repeat byte for byte, so everything here goes through [WorkspaceOperations] with the same
 * Operation handed in twice — which is exactly what a repeat Delivery is by the time it reaches
 * the core.
 */
class RepeatDeliveriesTest {
  @TempDir
  lateinit var temporary: Path

  private data class Wiring(
    val registry: WorkspaceRegistry,
    val activity: Activity,
    val pipeline: WorkspaceOperationsPipeline,
    val operations: WorkspaceOperations,
  )

  /**
   * The budget, the lock and the Delivery records are configuration (ADR 0002), so a test
   * shortens the 45 seconds and the 10 minutes rather than waiting them out.
   */
  private fun wiring(
    budget: Duration = 30.seconds,
    locks: PathLocks = PathLocks(),
    deliveries: Deliveries = Deliveries(),
    origin: Origin = Origin.ChatGpt,
  ): Wiring {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    val pipeline = WorkspaceOperationsPipeline(
      registry, activity, Operation.SEARCH_BUDGET, locks, GitTools(),
      commandBudget = budget, runner = CommandRunner(grace = 500.milliseconds), deliveries = deliveries,
    )
    return Wiring(registry, activity, pipeline, pipeline.operationsFor(origin))
  }

  private fun root(name: String = "project"): Path = Files.createDirectory(temporary.resolve(name))

  private suspend fun registered(registry: WorkspaceRegistry, root: Path, level: AccessLevel): Workspace {
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    return registry.perform(ManagementAct.SetLevel(workspace.id, level))
  }

  @Test
  fun `a repeated edit_file answers the first reply as a recorded result, never failed`() = runBlocking<Unit> {
    val (registry, activity, _, operations) = wiring()
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "alpha\n")
    registered(registry, root, AccessLevel.Write)
    val edit = Operation.EditFile("api", "notes.md", "alpha", "beta", deliveryKey = "r-1")

    val first = assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit))
    assertFalse(first.recorded)
    // The repeat finds zero matches if it runs, and zero matches is `failed` — nothing changed,
    // safe to retry — about a change the first Delivery already made.
    val repeat = assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit))

    assertEquals(first.value, repeat.value)
    assertTrue(repeat.recorded, "a repeat must say it is the recorded result of an operation already performed")
    assertEquals("beta\n", Files.readString(file))
    // One Operation, recorded once, with the repeat appended against it.
    val entry = activity.entries().single()
    assertEquals(1, entry.deliveries)
  }

  @Test
  fun `the same key with different arguments is failed, names the collision, and changes nothing`() =
    runBlocking<Unit> {
      val (registry, _, _, operations) = wiring()
      val root = root()
      registered(registry, root, AccessLevel.Write)
      assertIs<Outcome.Ok<FileWritten>>(operations.perform(Operation.WriteFile("api", "a.md", "one\n", "r-1")))

      val collision = assertIs<Outcome.Failed>(operations.perform(Operation.WriteFile("api", "b.md", "two\n", "r-1")))

      assertEquals(Failure.KeyConflict("r-1"), collision.reason)
      assertContains(collision.message, "r-1")
      assertFalse(collision.recorded)
      assertFalse(Files.exists(root.resolve("b.md")))
    }

  @Test
  fun `a key reused after its record expired is refused rather than run again`() = runBlocking<Unit> {
    val (registry, _, _, operations) = wiring(deliveries = Deliveries(retention = 100.milliseconds))
    val root = root()
    registered(registry, root, AccessLevel.Write)
    val write = Operation.WriteFile("api", "a.md", "one\n", "r-1")
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(write))
    Files.delete(root.resolve("a.md"))
    delay(200)

    val expired = assertIs<Outcome.Failed>(operations.perform(write))

    assertEquals(Failure.KeyExpired("r-1"), expired.reason)
    assertContains(expired.message, "request_id expired; use a new id only for an intentional new execution")
    assertFalse(Files.exists(root.resolve("a.md")), "an expired key must not run the operation again")
  }

  @Test
  fun `the bare key is remembered up to its bound, and no further`() = runBlocking<Unit> {
    // Expiring at once, and remembering two bare keys: the third key used pushes the first out.
    val (registry, _, _, operations) = wiring(deliveries = Deliveries(retention = Duration.ZERO, keyCap = 2))
    val root = root()
    registered(registry, root, AccessLevel.Write)
    for (key in listOf("r-1", "r-2", "r-3")) {
      assertIs<Outcome.Ok<FileWritten>>(operations.perform(Operation.WriteFile("api", "$key.md", "x\n", key)))
    }
    // Arriving is what expires a record, so this one pushes r-3's record out and r-1's key with it.
    assertEquals(
      Failure.KeyExpired("r-2"),
      assertIs<Outcome.Failed>(operations.perform(Operation.WriteFile("api", "r-2.md", "x\n", "r-2"))).reason,
    )

    val forgotten = operations.perform(Operation.WriteFile("api", "r-1.md", "again\n", "r-1"))

    assertIs<Outcome.Ok<FileWritten>>(forgotten)
    assertEquals("again\n", Files.readString(root.resolve("r-1.md")))
  }

  @Test
  fun `a repeat of a Delivery in flight waits on it, and is uncertain if its own budget runs out first`() =
    runBlocking<Unit> {
      val locks = PathLocks()
      val (registry, activity, _, operations) = wiring(budget = 500.milliseconds, locks = locks)
      val root = root()
      val file = Files.writeString(root.resolve("notes.md"), "alpha\n")
      registered(registry, root, AccessLevel.Write)
      val edit = Operation.EditFile("api", "notes.md", "alpha", "beta", deliveryKey = "r-1")

      // Holding the lock the first Delivery needs keeps it genuinely in flight.
      val holding = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val holder = launch(Dispatchers.Default) {
        locks.withPathLock(file.toRealPath().toString()) {
          holding.complete(Unit)
          release.await()
        }
      }
      holding.await()
      val first = async(Dispatchers.Default) { operations.perform(edit) }
      while (activity.entries().isEmpty()) delay(10)

      val outwaited = assertIs<Outcome.Uncertain>(operations.perform(edit))
      assertEquals(Uncertainty.FirstDeliveryInFlight("r-1"), outwaited.reason)
      assertEquals("alpha\n", Files.readString(file))

      val waiting = async(Dispatchers.Default) { operations.perform(edit) }
      delay(100)
      release.complete(Unit)
      holder.join()

      val answered = assertIs<Outcome.Ok<FileWritten>>(waiting.await())
      assertTrue(answered.recorded)
      assertEquals(assertIs<Outcome.Ok<FileWritten>>(first.await()).value, answered.value)
      assertEquals("beta\n", Files.readString(file))
      val entry = activity.entries().single()
      assertEquals(2, entry.deliveries)
    }

  @Test
  fun `a repeat waiting on a first Delivery that fails is answered as a Delivery of its own`() =
    runBlocking<Unit> {
      // `failed` left nothing a second execution could do twice, so its key is released — and a
      // repeat already waiting must not then present that `failed` as the recorded result of an
      // operation already performed.
      val locks = PathLocks()
      val (registry, activity, _, operations) = wiring(locks = locks)
      val root = root()
      val file = Files.writeString(root.resolve("notes.md"), "alpha\n")
      registered(registry, root, AccessLevel.Write)
      val edit = Operation.EditFile("api", "notes.md", "alpha", "beta", deliveryKey = "r-1")

      val holding = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val holder = launch(Dispatchers.Default) {
        locks.withPathLock(file.toRealPath().toString()) {
          holding.complete(Unit)
          release.await()
          // Another process changes the region while both Deliveries wait.
          Files.writeString(file, "gamma\n")
        }
      }
      holding.await()
      val first = async(Dispatchers.Default) { operations.perform(edit) }
      while (activity.entries().isEmpty()) delay(10)
      val repeat = async(Dispatchers.Default) { operations.perform(edit) }
      delay(100)
      release.complete(Unit)
      holder.join()

      assertEquals(Failure.NoMatch("api", "notes.md"), assertIs<Outcome.Failed>(first.await()).reason)
      val answered = assertIs<Outcome.Failed>(repeat.await())
      assertFalse(answered.recorded, "a failed first Delivery performed nothing to have a recorded result of")
      assertEquals("gamma\n", Files.readString(file))
    }

  @Test
  fun `a repeat of a Promoted command gets the same Handle whatever the level, and get_result re-checks it`() =
    runBlocking<Unit> {
      val (registry, activity, pipeline, operations) = wiring(budget = 200.milliseconds)
      val root = root()
      val workspace = registered(registry, root, AccessLevel.Command)
      val command = Operation.RunCommand("api", "echo ran >> runs; sleep 1", deliveryKey = "r-1")

      val handle = promoted(operations.perform(command))
      // The dial drops between Deliveries. A repeat is not a new decision by anybody.
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))

      val repeat = assertIs<Outcome.Ok<CommandReply>>(operations.perform(command))
      assertTrue(repeat.recorded)
      assertEquals(handle, assertIs<CommandReply.Promoted>(repeat.value).handle)
      // get_result is a fresh call the model chose to make, so it is refused at Read.
      val collecting = assertIs<Outcome.Failed>(operations.perform(Operation.GetResult("api", handle.value)))
      assertIs<Failure.LevelTooLow>(collecting.reason)

      awaitUntil { activity.entries().single { it.tool == "run_command" }.outcome !is ActivityOutcome.InFlight }
      assertEquals(listOf("ran"), Files.readAllLines(root.resolve("runs")), "one command, never a second")
      pipeline.stop()
    }

  @Test
  fun `a lowered level does not change the answer to a finished repeat`() = runBlocking<Unit> {
    val (registry, _, _, operations) = wiring()
    val root = root()
    val workspace = registered(registry, root, AccessLevel.Write)
    val write = Operation.WriteFile("api", "a.md", "one\n", "r-1")
    val first = assertIs<Outcome.Ok<FileWritten>>(operations.perform(write))

    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
    val repeat = assertIs<Outcome.Ok<FileWritten>>(operations.perform(write))

    assertEquals(first.value, repeat.value)
    assertTrue(repeat.recorded)
  }

  @Test
  fun `a finished repeat of a command whose answer was discarded carries it through and settles Undelivered`() =
    runBlocking<Unit> {
      val (registry, activity, pipeline, operations) = wiring()
      val root = root()
      registered(registry, root, AccessLevel.Command)
      val command = Operation.RunCommand("api", "touch running; sleep 0.3; echo done", deliveryKey = "r-1")

      val discarded = launch(Dispatchers.IO) { operations.perform(command) }
      while (!Files.exists(root.resolve("running"))) delay(10)
      discarded.cancelAndJoin()
      awaitUntil { activity.entries().single().outcome is ActivityOutcome.Undelivered }

      val repeat = assertIs<Outcome.Ok<CommandReply>>(operations.perform(command))

      assertTrue(repeat.recorded)
      assertEquals("done\n", assertIs<CommandReply.Finished>(repeat.value).result.output)
      val entry = activity.entries().single()
      assertEquals(ActivityOutcome.Ok(entry.outcome.detail!!), entry.outcome, "settled, not Undelivered")
      assertEquals(1, entry.deliveries)
      pipeline.stop()
    }

  @Test
  fun `a frontend's Operation reaches the same check`() = runBlocking<Unit> {
    val (registry, activity, _, operations) = wiring(origin = Origin.Frontend)
    val root = root()
    val file = Files.writeString(root.resolve("notes.md"), "alpha\n")
    registered(registry, root, AccessLevel.Write)
    val edit = Operation.EditFile("api", "notes.md", "alpha", "beta", deliveryKey = "r-1")
    assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit))

    assertTrue(assertIs<Outcome.Ok<FileWritten>>(operations.perform(edit)).recorded)
    assertEquals("beta\n", Files.readString(file))
    assertEquals(Origin.Frontend, activity.entries().single().origin)
  }

  @Test
  fun `nothing about a Delivery survives a restart`() = runBlocking<Unit> {
    val first = wiring()
    val root = root()
    registered(first.registry, root, AccessLevel.Write)
    val write = Operation.WriteFile("api", "a.md", "one\n", "r-1")
    assertIs<Outcome.Ok<FileWritten>>(first.operations.perform(write))

    // A restart kills the transport with it, so no repeat survives to arrive; a key arriving
    // at the next Start is a first Delivery there.
    val restarted = wiring()
    val again = assertIs<Outcome.Ok<FileWritten>>(restarted.operations.perform(write))

    assertFalse(again.recorded)
  }

  private fun promoted(outcome: Outcome<CommandReply>): Handle =
    assertIs<CommandReply.Promoted>(assertIs<Outcome.Ok<CommandReply>>(outcome).value).handle

  private suspend fun awaitUntil(condition: suspend () -> Boolean) {
    val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
    while (!condition()) {
      if (System.nanoTime() > deadline) fail("Waited 30s")
      delay(20)
    }
  }
}
