package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ActivityTest {
  @TempDir
  lateinit var stateDirectory: Path

  private val file: Path get() = stateDirectory.resolve("activity")

  private fun activity(retention: kotlin.time.Duration = Activity.DEFAULT_RETENTION) = Activity(file, retention)

  /**
   * A retention that ran out before the entry was written. ADR 0002 keeps a `Clock` out of the
   * core, so expiry is asked about by configuring the duration rather than by waiting it out.
   */
  private val expired = (-1).seconds

  private fun onDisk(): String = Files.readString(file)

  @Test
  fun `an entry is opened before the work and completed after, and what was written is not rewritten`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "read_file", "path=notes.md")
    val opened = onDisk()
    assertTrue(opened.isNotEmpty(), "the entry is on disk before the work begins, not after it")
    assertEquals(ActivityOutcome.InFlight, activity.entries().single().outcome)

    activity.complete(id, ActivityOutcome.Ok("12 lines"))
    assertTrue(onDisk().startsWith(opened), "the completion appends; it does not rewrite the open record")
    assertEquals(ActivityOutcome.Ok("12 lines"), activity.entries().single().outcome)
  }

  @Test
  fun `an entry carries time, Origin, Workspace, tool, arguments, elapsed, outcome and its Runtime start`() {
    val activity = activity()
    val id = activity.open(Origin.Frontend, "api", "read_file", "path=notes.md offset=3")
    activity.complete(id, ActivityOutcome.Ok("12 lines"))

    val entry = activity.entries().single()
    assertEquals(id, entry.id)
    assertNotNull(entry.at)
    assertEquals(activity.start, entry.runtimeStart)
    assertEquals(Origin.Frontend, entry.origin)
    assertEquals("api", entry.workspace)
    assertEquals("read_file", entry.tool)
    assertEquals("path=notes.md offset=3", entry.arguments)
    assertNotNull(entry.elapsed, "a completed entry knows how long it took")
    assertEquals(ActivityOutcome.Ok("12 lines"), entry.outcome)
    assertEquals(0, entry.deliveries)
    assertNull(entry.acknowledgedAt)
  }

  @Test
  fun `an entry opened and never completed is read back as Lost by a later start`() {
    val died = activity()
    died.open(Origin.ChatGpt, "api", "write_file", "path=notes.md")

    val restarted = activity()
    val entry = restarted.entries().single()
    assertEquals(ActivityOutcome.Lost, entry.outcome)
    assertNull(entry.elapsed, "a Lost Operation produced no result, so it has no elapsed time")
    assertNotEquals(restarted.start, entry.runtimeStart, "it belongs to the start that opened it")
    assertTrue(entry.needsAttention)
  }

  @Test
  fun `an entry this start opened and has not finished is in flight, not Lost`() {
    val activity = activity()
    activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    assertEquals(ActivityOutcome.InFlight, activity.entries().single().outcome)
    assertFalse(activity.entries().single().needsAttention, "work still running is not an unresolved outcome")
  }

  @Test
  fun `a refused call is an ordinary failed entry and raises no flag`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "withheld", "read_file", "path=notes.md")
    activity.complete(id, ActivityOutcome.Failed("No such Workspace."))

    val entry = activity.entries().single()
    assertEquals("withheld", entry.workspace, "the name the model tried is exactly what the account is for")
    assertFalse(entry.needsAttention)
    assertEquals(0, activity.unresolvedCount())
  }

  @Test
  fun `entries older than retention are dropped`() {
    val activity = activity(retention = expired)
    val id = activity.open(Origin.ChatGpt, "api", "read_file", "path=notes.md")
    activity.complete(id, ActivityOutcome.Ok("12 lines"))

    assertEquals(emptyList(), activity.entries())
    activity.prune()
    assertEquals("", onDisk(), "pruning takes the expired records off disk too")
  }

  @Test
  fun `an unresolved outcome never expires until it is settled`() {
    val activity = activity(retention = expired)
    val id = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.complete(id, ActivityOutcome.Uncertain("It timed out; effects uncertain, do not retry."))

    activity.prune()
    assertEquals(1, activity.entries().size, "an Uncertain entry is what the record exists for")
    assertEquals(1, activity.unresolvedCount())

    activity.acknowledge(id)
    assertEquals(emptyList(), activity.entries(), "acknowledgement settles it, and a settled entry is past its 30 days")
    assertEquals(0, activity.unresolvedCount())
    activity.prune()
    assertEquals("", onDisk())
  }

  @Test
  fun `acknowledgement appends a fact and leaves the entry unchanged on disk`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.complete(id, ActivityOutcome.Uncertain("It was stopped; effects uncertain, do not retry."))
    val before = onDisk()

    activity.acknowledge(id)
    val after = onDisk()
    assertTrue(after.startsWith(before), "an Ack is appended, never a flag set on what is written")
    assertEquals(after.lines().size, before.lines().size + 1)

    val entry = activity.entries().single()
    assertNotNull(entry.acknowledgedAt, "acknowledged state is folded out of the appended record")
    assertFalse(entry.needsAttention)
    assertTrue(
      entry.outcome.unresolved,
      "acknowledgement records that the user saw it; it does not make the outcome certain",
    )
  }

  @Test
  fun `a repeat Delivery appends a fact against the one entry rather than a second entry`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "write_file", "path=notes.md")
    activity.complete(id, ActivityOutcome.Ok("wrote 40 bytes"))

    activity.delivery(id, carried = false)
    activity.delivery(id, carried = false)

    val entry = activity.entries().single()
    assertEquals(2, entry.deliveries, "four entries for one thing asked once is the confusion the repeat causes")
  }

  @Test
  fun `a Delivery that carries the stored reply through settles an Undelivered entry`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "write_file", "path=notes.md")
    val completed = ActivityOutcome.Ok("wrote 40 bytes")
    activity.complete(id, completed)
    activity.undelivered(id)

    assertEquals(ActivityOutcome.Undelivered(completed), activity.entries().single().outcome)
    assertEquals(1, activity.unresolvedCount())

    activity.delivery(id, carried = true)
    val entry = activity.entries().single()
    assertEquals(completed, entry.outcome, "the answer reached ChatGPT after all")
    assertEquals(1, entry.deliveries)
    assertEquals(0, activity.unresolvedCount())
  }

  @Test
  fun `an Unclaimed outcome settles by being collected, or otherwise by acknowledgement`() {
    val activity = activity()
    val completed = ActivityOutcome.Ok("exit 0")

    val collected = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.complete(collected, completed)
    activity.unclaimed(collected)
    assertEquals(ActivityOutcome.Unclaimed(completed), activity.entries().first().outcome)
    activity.delivery(collected, carried = true)

    val withheld = activity.open(Origin.ChatGpt, "api", "run_command", "command=make test")
    activity.complete(withheld, completed)
    activity.unclaimed(withheld)
    assertEquals(1, activity.unresolvedCount(), "lowering the Workspace closed the collecting route")
    activity.acknowledge(withheld)
    assertEquals(0, activity.unresolvedCount())
  }

  @Test
  fun `a carry appended before the outcome it would settle settles nothing`() {
    // Why the order a promoted command writes its records in is a decision and not a detail:
    // settling is what a *later* arrival does, so a fact that arrives first
    // is superseded by the outcome it precedes. A Runtime that published a collectable result
    // before writing the records would strand the entry here, unresolved and unsettleable.
    val activity = activity()
    val completed = ActivityOutcome.Ok("exit 0")

    val early = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.delivery(early, carried = true)
    activity.complete(early, completed)
    activity.unclaimed(early)

    assertEquals(ActivityOutcome.Unclaimed(completed), activity.entries().single().outcome)
    assertEquals(1, activity.unresolvedCount(), "nobody collected an outcome that did not exist yet")
  }

  @Test
  fun `a record cut off mid-write is dropped and the rest of the account survives`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "read_file", "path=notes.md")
    activity.complete(id, ActivityOutcome.Ok("12 lines"))
    // What a machine dying mid-append leaves: bytes with no line terminator after them.
    Files.writeString(file, onDisk() + "open\tid=half-written\tat=2026-")

    val entry = activity().entries().single()
    assertEquals(id, entry.id)
    assertEquals(ActivityOutcome.Ok("12 lines"), entry.outcome)
  }

  @Test
  fun `tabs and newlines in an argument summary survive the round trip`() {
    val activity = activity()
    val awkward = "command=printf 'a\tb\nc'\\d"
    val id = activity.open(Origin.ChatGpt, "api", "run_command", awkward)
    activity.complete(id, ActivityOutcome.Ok("a\tb\nc"))

    val entry = activity().entries().single()
    assertEquals(awkward, entry.arguments)
    assertEquals(ActivityOutcome.Ok("a\tb\nc"), entry.outcome)
  }

  @Test
  fun `full output lives in the record, and nothing spills outside the state directory`() {
    val root = Files.createDirectory(stateDirectory.resolve("root"))
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    // An Operation's output is capped at 64 KiB and there is no overflow-to-file: a spill
    // file outside every Root is a path the model is told about and cannot read.
    val output = "x".repeat(Operation.OUTPUT_CAP_BYTES)
    activity.complete(id, ActivityOutcome.Ok(output))
    activity.prune()

    assertEquals(output, (activity().entries().single().outcome as ActivityOutcome.Ok).detail)
    assertEquals(emptyList(), Files.list(root).use { it.toList() }, "nothing is written inside a Root")
    assertEquals(
      listOf("activity", "root"),
      Files.list(stateDirectory).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() },
      "the account and its temporary files stay inside the state directory",
    )
  }

  @Test
  fun `the account is kept in the order the Operations arrived`() {
    val activity = activity()
    val first = activity.open(Origin.ChatGpt, "api", "read_file", "path=a")
    val second = activity.open(Origin.Frontend, "api", "read_file", "path=b")
    activity.complete(second, ActivityOutcome.Ok("1 line"))
    activity.complete(first, ActivityOutcome.Ok("1 line"))

    assertEquals(listOf(first, second), activity.entries().map { it.id })
  }
  @Test
  fun `an answer that goes astray after one was carried through is unresolved again`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "write_file", "path=notes.md")
    val completed = ActivityOutcome.Ok("wrote 40 bytes")
    activity.complete(id, completed)
    activity.delivery(id, carried = true)
    activity.undelivered(id)

    assertEquals(
      ActivityOutcome.Undelivered(completed),
      activity.entries().single().outcome,
      "settling is what a later arrival does; an earlier one cannot settle what had not gone astray",
    )
  }

  @Test
  fun `work still running is not expired out from under itself`() {
    val activity = activity(retention = expired)
    activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.prune()

    // Had the prune dropped it, the completion still to come would be an orphan the fold
    // ignores, and the Operation would have vanished from the account rather than expired.
    assertEquals(ActivityOutcome.InFlight, activity.entries().single().outcome)
    assertTrue(onDisk().isNotEmpty())
  }
  @Test
  fun `a record cut off mid-write does not swallow the Operation appended after it`() {
    val first = activity()
    val done = first.open(Origin.ChatGpt, "api", "read_file", "path=notes.md")
    first.complete(done, ActivityOutcome.Ok("12 lines"))
    // A Runtime that died while writing a completion — the likelier half, since the open
    // record is the one that got there first.
    Files.writeString(file, onDisk() + "complete\tid=half-written\tat=2026-")

    // The next Runtime appends after the fragment. Dropping the fragment only at read time
    // would leave this open record fused to the end of it, read back as somebody else's
    // completion, and the Operation below — not the half-written one — is what would vanish.
    val restarted = activity()
    val id = restarted.open(Origin.ChatGpt, "api", "run_command", "command=make")
    restarted.complete(id, ActivityOutcome.Ok("exit 0"))

    val entries = activity().entries()
    assertEquals(listOf(done, id), entries.map { it.id })
    assertEquals(ActivityOutcome.Ok("exit 0"), entries.last().outcome)
  }

  @Test
  fun `a tail cut inside a multi-byte character does not stop the Runtime reading the account`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "read_file", "path=notes.md")
    activity.complete(id, ActivityOutcome.Ok("12 lines"))
    // "é" is two bytes; the machine died between them.
    Files.write(file, Files.readAllBytes(file) + "open\tid=x\targs=caf".toByteArray() + byteArrayOf(0xC3.toByte()))

    assertEquals(id, activity().entries().single().id)
  }

  @Test
  fun `a record kind this version cannot read survives being pruned around`() {
    val activity = activity(retention = expired)
    val id = activity.open(Origin.ChatGpt, "api", "read_file", "path=notes.md")
    activity.complete(id, ActivityOutcome.Ok("12 lines"))
    // What a newer Runtime, downgraded, leaves behind: a fact this version has no name for.
    val newer = "promoted\tid=${id.value}\tat=${java.time.Instant.now()}\thandle=7"
    Files.writeString(file, onDisk() + newer + "\n")

    activity.prune()

    assertEquals(emptyList(), activity.entries(), "the expired entry goes")
    assertEquals(
      newer,
      onDisk().trim(),
      "the line it could not read stays: quietly destroying an account is the one failure it cannot come back from",
    )
  }

  @Test
  fun `an acknowledgement does not settle an outcome that did not exist when it was made`() {
    val activity = activity(retention = expired)
    val id = activity.open(Origin.ChatGpt, "api", "run_command", "command=make")
    activity.acknowledge(id)
    activity.complete(id, ActivityOutcome.Uncertain("It timed out; effects uncertain, do not retry."))

    val entry = activity.entries().single()
    assertNull(entry.acknowledgedAt, "nobody saw an outcome that had not happened yet")
    assertTrue(entry.needsAttention)
    assertEquals(1, activity.unresolvedCount())
  }

  @Test
  fun `an answer that goes astray again is unresolved again, however it was settled before`() {
    val activity = activity()
    val id = activity.open(Origin.ChatGpt, "api", "write_file", "path=notes.md")
    val completed = ActivityOutcome.Ok("wrote 40 bytes")
    activity.complete(id, completed)
    activity.undelivered(id)
    activity.acknowledge(id)
    activity.delivery(id, carried = true)
    assertEquals(0, activity.unresolvedCount())

    activity.undelivered(id)

    val entry = activity.entries().single()
    assertEquals(ActivityOutcome.Undelivered(completed), entry.outcome)
    assertNull(entry.acknowledgedAt, "an earlier acknowledgement is not consent to the next one")
    assertEquals(1, activity.unresolvedCount())
  }

  /** What a frontend attaching now would be handed first. */
  private fun RuntimeFeed.attach(): RuntimeEvent.Snapshot = runBlocking { assertIs(observe().first()) }

  @Test
  fun `a frontend attaching sees the whole account, an earlier start's Lost entry included, and this start`() {
    Activity(file, feed = RuntimeFeed()).open(Origin.ChatGpt, "api", "write_file", "path=notes.md")

    val feed = RuntimeFeed()
    val restarted = Activity(file, feed = feed)
    val attached = feed.attach()

    assertEquals(restarted.entries(), attached.activity)
    assertEquals(ActivityOutcome.Lost, attached.activity.single().outcome)
    assertEquals(restarted.start, attached.start?.id, "the boundary the feed draws is this start's")
  }

  @Test
  fun `what a frontend is told about each entry agrees with the account, record after record`() {
    val feed = RuntimeFeed()
    val activity = Activity(file, feed = feed)
    val first = activity.open(Origin.ChatGpt, "api", "edit_file", "path=a")
    assertEquals(activity.entries(), feed.attach().activity, "an entry is on the feed as soon as it is opened")
    activity.complete(first, ActivityOutcome.Ok("edited"))
    activity.undelivered(first)
    assertEquals(activity.entries(), feed.attach().activity)
    activity.delivery(first, carried = true)
    assertEquals(activity.entries(), feed.attach().activity, "a carried Delivery settles the entry on the feed too")

    val second = activity.open(Origin.Frontend, "api", "run_command", "command=make")
    activity.complete(second, ActivityOutcome.Uncertain("stopped"))
    assertTrue(feed.attach().activity.last().needsAttention)
    activity.acknowledge(second)
    assertEquals(activity.entries(), feed.attach().activity)
    assertFalse(feed.attach().activity.last().needsAttention, "an acknowledgement reaches the feed")
  }

  @Test
  fun `an entry retention drops leaves the feed as well as the account`() {
    val feed = RuntimeFeed()
    val activity = Activity(file, expired, feed)
    activity.complete(activity.open(Origin.ChatGpt, "api", "read_file", "path=a"), ActivityOutcome.Ok("1 line"))
    activity.prune()
    assertEquals(emptyList(), feed.attach().activity)
  }
}
