package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.UUID
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The append-only account of what the Runtime did. It is load-bearing
 * rather than a convenience precisely because there is no per-call approval prompt and a
 * Workspace left at Command can run anything with nobody present.
 *
 * **A reporting mechanism, never a recovery one.** Nothing here is ever replayed or auto-retried
 * from, and ambiguity is made visible rather than resolved by guessing: an entry is written
 * *before* the work begins and completed after, so a Runtime taken from the machine reads back
 * as **Lost** — "in flight when the Runtime died, outcome unknown" — instead of as silence.
 *
 * Nothing is ever written but records — an entry opened, an entry completed, a fact appended
 * against one, an acknowledgement — and every state the frontend shows is folded back out of
 * them. [ActivityOutcome.Lost] is the clearest case: it is never written down, because it is
 * what an open record with no completion beside it *means* once a later start reads it.
 *
 * Retention is bounded by **age, not count**: 30 days, and an unresolved outcome never expires
 * until it is settled. Count-bounded retention was rejected — 200 entries is one unattended
 * afternoon.
 */
class Activity(
  private val file: Path,
  private val retention: Duration = DEFAULT_RETENTION,
  /** Where a frontend learns what is running, and what the account says about each entry. */
  private val feed: RuntimeFeed = RuntimeFeed(),
) {
  /** This Runtime start. An entry naming another one is an entry a previous Runtime opened. */
  val start: RuntimeStartId = RuntimeStartId(UUID.randomUUID().toString())
  private val startedAt: Instant = Instant.now()

  private val lock = Any()
  private var lastPruned: Instant = Instant.now()

  /**
   * The account as the feed was last told it, folded as records are appended. Kept beside the
   * file rather than read back from it, so telling a frontend about one append costs one
   * record's fold and not a re-read of thirty days. It is never where [entries] comes from:
   * the file is the account, and this is only what was last said about it.
   */
  private val told = LinkedHashMap<String, Folding>()

  init {
    Files.createDirectories(file.toAbsolutePath().parent)
    healPartialRecord()
    // A start is the one moment the account is certain to be read: compacting here is what
    // keeps 30 days of retention from being a claim nothing enforces on a long-lived Runtime.
    prune(announce = true)
  }

  /**
   * Cuts a record a dying Runtime never finished writing. Dropping it at read time is not
   * enough: the next append would land on the end of the fragment, making one unreadable line
   * out of the two, and what would then be lost from the account is the *new* Operation rather
   * than the half-written one. A fragment was never a written record, so cutting it is not a
   * mutation of one.
   */
  private fun healPartialRecord() {
    val bytes = try {
      Files.readAllBytes(file)
    } catch (_: NoSuchFileException) {
      return
    }
    if (bytes.isEmpty() || bytes.last() == NEWLINE) return
    FileChannel.open(file, WRITE).use { channel ->
      channel.truncate((bytes.lastIndexOf(NEWLINE) + 1).toLong())
      channel.force(true)
    }
  }

  /**
   * Opens an entry **before** the work begins and returns its id. [workspace] is the name the
   * call gave, resolved or not: a model repeatedly naming a Workspace you withheld is exactly
   * what the unattended account exists to show.
   */
  fun open(origin: Origin, workspace: String?, tool: String, arguments: String): ActivityEntryId {
    val id = ActivityEntryId(UUID.randomUUID().toString())
    append(
      Record(
        OPEN,
        buildMap {
          put(ID, id.value)
          put(AT, Instant.now().toString())
          put(START, start.value)
          put(ORIGIN, origin.name)
          workspace?.let { put(WORKSPACE, it) }
          put(TOOL, tool)
          put(ARGUMENTS, arguments)
        },
      ),
    )
    feed.publish(RuntimeEvent.Change.OperationStarted(RunningOperation(id, origin, workspace, tool, arguments, Instant.now())))
    return id
  }

  /**
   * Completes an opened entry. The detail is the Operation's own output and is not bounded
   * here: an Operation's output is capped at [Operation.OUTPUT_CAP_BYTES] where it is produced
   * and then lives whole in this record, because there is **no overflow-to-file** — a spill
   * file outside every Root is a path the model is told about and cannot read.
   */
  fun complete(id: ActivityEntryId, outcome: ActivityOutcome) {
    val (kind, detail) = when (outcome) {
      is ActivityOutcome.Ok -> OK to outcome.detail
      is ActivityOutcome.Failed -> FAILED to outcome.detail
      is ActivityOutcome.Uncertain -> UNCERTAIN to outcome.detail
      // Lost is derived, and the other two are facts appended against a completion, not
      // completions themselves. Refusing them here is what keeps the file's vocabulary small.
      else -> throw IllegalArgumentException("An Operation completes as ok, failed or uncertain, not as $outcome")
    }
    append(Record(COMPLETE, mapOf(ID to id.value, AT to Instant.now().toString(), OUTCOME to kind, DETAIL to detail)))
    feed.publish(RuntimeEvent.Change.OperationEnded(id))
  }

  /**
   * A running command was handed to the Runtime past its call. Told to a frontend and not
   * written: what the account says of it is its outcome, once it has one.
   */
  fun promoted(id: ActivityEntryId) = feed.publish(RuntimeEvent.Change.OperationPromoted(id))

  /** The one kill has reached [phase] on a running command. Told, not written, like [promoted]. */
  fun stopping(id: ActivityEntryId, phase: StopPhase) = feed.publish(RuntimeEvent.Change.OperationStopping(id, phase))

  /** The Operation completed and its answer never reached ChatGPT. */
  fun undelivered(id: ActivityEntryId) = appendFact(UNDELIVERED, id)

  /** A Promoted Operation completed and nobody has collected its outcome. */
  fun unclaimed(id: ActivityEntryId) = appendFact(UNCLAIMED, id)

  /**
   * One further arrival of an Operation already recorded. [carried] says the repeat handed the
   * stored reply over, which is what settles an Undelivered or Unclaimed entry. A Delivery per
   * entry was rejected: the user would see four entries for one thing they asked for once.
   */
  fun delivery(id: ActivityEntryId, carried: Boolean) {
    append(
      Record(DELIVERY, mapOf(ID to id.value, AT to Instant.now().toString(), CARRIED to carried.toString())),
    )
  }

  /** The user has seen an unresolved outcome. An appended fact, never an edit. */
  fun acknowledge(id: ActivityEntryId) = appendFact(ACK, id)

  /** The account as the frontend reads it, oldest first, with expired entries already gone. */
  fun entries(): List<ActivityEntry> = synchronized(lock) { fold(read().mapNotNull { it.record }) }

  /**
   * One entry by the id that names it, or null where nothing in the account does — which is
   * every id that was never opened, and every entry retention has since dropped.
   */
  fun entry(id: ActivityEntryId): ActivityEntry? = entries().find { it.id == id }

  /** What the gutter's `!` counts: unresolved outcomes nobody has settled. */
  fun unresolvedCount(): Int = entries().count { it.needsAttention }

  /**
   * Drops the records of every entry retention no longer keeps. Rewriting the file is not a
   * mutation of a written entry — an expired entry is gone whole, never edited in place — and
   * the replacement lands by atomic rename, so a crash mid-prune loses no account.
   */
  fun prune() = prune(announce = false)

  /** [announce] tells the feed the whole account even when nothing expired: a start is when it first hears it. */
  private fun prune(announce: Boolean) = synchronized(lock) {
    val lines = read()
    val folded = kept(lines.mapNotNull { it.record })
    val kept = folded.keys
    lastPruned = Instant.now()
    val surviving = lines.filter { line ->
      // A line this version cannot read is not a line it may delete. A newer Runtime's record
      // survives a downgrade, because quietly destroying part of the account is the one failure
      // an append-only record cannot be brought back from.
      val record = line.record ?: return@filter true
      // What goes: an entry retention no longer keeps, and anything that never had an open
      // record of its own to belong to.
      record.fields[ID] in kept
    }
    val dropped = surviving.size != lines.size
    try {
      if (dropped) rewrite(surviving)
    } finally {
      if (dropped || announce) tellWhole(folded)
    }
  }

  // Written back byte for byte rather than re-encoded: an expired entry goes whole, and what
  // survives is what was written, not this version's rendering of it.
  private fun rewrite(surviving: List<Line>) = writeDurably(file, surviving.joinToString("") { it.raw + "\n" })

  /** Hands a frontend the whole account, as retention now keeps it: at a start, and whenever retention has dropped entries. */
  private fun tellWhole(kept: Map<String, Folding>) {
    told.clear()
    // The foldings themselves rather than their entries, so a later append lands on exactly the
    // state a re-read would have reached.
    told.putAll(kept)
    feed.publish(RuntimeEvent.Change.ActivityRead(RuntimeStart(start, startedAt), told.values.map { it.build(start) }))
  }

  /** What one appended record changes about its entry, said to every frontend. */
  private fun tell(record: Record) {
    val id = record.fields[ID] ?: return
    val folding = told.absorb(id, record) ?: return
    feed.publish(RuntimeEvent.Change.EntryRecorded(folding.build(start)))
  }

  private fun appendFact(kind: String, id: ActivityEntryId) =
    append(Record(kind, mapOf(ID to id.value, AT to Instant.now().toString())))

  private fun append(record: Record) = synchronized(lock) {
    val line = ByteBuffer.wrap((encode(record) + "\n").toByteArray(UTF_8))
    FileChannel.open(file, CREATE, WRITE, APPEND).use { channel ->
      // `write` is not obliged to take the whole buffer, and a record written in part is the
      // corruption this whole file is arranged to avoid.
      while (line.hasRemaining()) channel.write(line)
      // The whole value of writing before the work is that the record outlives the machine.
      // A page cache still holding it when the power goes is silence where Lost should be.
      channel.force(false)
    }
    tell(record)
    if (Instant.now().isAfter(lastPruned.plusMillis(PRUNE_INTERVAL.inWholeMilliseconds))) prune()
  }

  private fun read(): List<Line> {
    val bytes = try {
      Files.readAllBytes(file)
    } catch (_: NoSuchFileException) {
      return emptyList()
    }
    // Decoded leniently, never strictly: a tail cut inside a multi-byte character is a record
    // nobody finished writing, and refusing to start is a worse answer to it than dropping it.
    val text = String(bytes, UTF_8)
    // `split` yields a trailing fragment either way — the empty string after a complete file's
    // last newline, or a record a dying Runtime never finished — and dropping it is the honest
    // reading of both: what was not written whole was not written.
    return text.split('\n').dropLast(1).map { Line(it, decode(it)) }
  }

  private fun fold(records: List<Record>): List<ActivityEntry> = kept(records).values.map { it.build(start) }

  /** Every entry's folding, in the order the entries were opened, less those retention no longer keeps. */
  private fun kept(records: List<Record>): LinkedHashMap<String, Folding> {
    val folding = LinkedHashMap<String, Folding>()
    for (record in records) {
      folding.absorb(record.fields[ID] ?: continue, record)
    }
    val expiry = Instant.now().minusMillis(retention.inWholeMilliseconds)
    folding.values.removeIf { folded ->
      val entry = folded.build(start)
      // Work this Runtime is still running cannot be expired out from under itself: its
      // completion would arrive to an entry that no longer exists, and the Operation would
      // vanish from the account altogether — silence being the one thing Activity forbids.
      !(entry.needsAttention || entry.outcome == ActivityOutcome.InFlight || !entry.at.isBefore(expiry))
    }
    return folding
  }

  /**
   * One record folded into the entry it names: an open starts one, anything else moves one on.
   * The one fold step, so the account read back and what the feed was told cannot drift apart.
   * A record naming no open entry is one whose entry has already expired; the fold is not where
   * that is repaired, it is where it is ignored.
   */
  private fun MutableMap<String, Folding>.absorb(id: String, record: Record): Folding? =
    if (record.kind == OPEN) Folding(record).also { this[id] = it } else this[id]?.also { it.absorb(record) }

  /** One entry's records, collapsed as they are read. Nothing here is written back. */
  private class Folding(open: Record) {
    private val id = ActivityEntryId(open.fields.getValue(ID))
    private val at = Instant.parse(open.fields.getValue(AT))
    private val runtimeStart = RuntimeStartId(open.fields.getValue(START))
    private val origin = Origin.valueOf(open.fields.getValue(ORIGIN))
    private val workspace = open.fields[WORKSPACE]
    private val tool = open.fields.getValue(TOOL)
    private val arguments = open.fields[ARGUMENTS].orEmpty()

    private var completedAt: Instant? = null
    private var completed: ActivityOutcome? = null
    private var astray: String? = null
    private var carried = false
    private var deliveries = 0
    private var acknowledgedAt: Instant? = null

    fun absorb(record: Record) {
      val at = record.fields[AT]?.let(Instant::parse)
      when (record.kind) {
        COMPLETE -> {
          supersede()
          completedAt = at
          val detail = record.fields[DETAIL].orEmpty()
          completed = when (record.fields[OUTCOME]) {
            OK -> ActivityOutcome.Ok(detail)
            FAILED -> ActivityOutcome.Failed(detail)
            UNCERTAIN -> ActivityOutcome.Uncertain(detail)
            else -> null
          }
        }
        UNDELIVERED, UNCLAIMED -> {
          supersede()
          astray = record.kind
        }
        DELIVERY -> {
          deliveries++
          if (record.fields[CARRIED].toBoolean()) carried = true
        }
        ACK -> acknowledgedAt = at
      }
    }

    /**
     * A fact that changes what there is to see un-settles the entry. Nobody acknowledged an
     * outcome that did not exist yet, and nothing was carried through for an answer that had
     * not yet gone astray — settling is what a *later* arrival does. The records stay where
     * they are; it is only their reading that moves on.
     */
    private fun supersede() {
      acknowledgedAt = null
      carried = false
    }

    fun build(current: RuntimeStartId): ActivityEntry {
      val settled = completed
      val outcome = when {
        // Never written down, always derived: an entry a previous Runtime opened and never
        // closed is one whose Runtime was taken from the machine while it was in flight.
        settled == null -> if (runtimeStart == current) ActivityOutcome.InFlight else ActivityOutcome.Lost
        carried || astray == null -> settled
        astray == UNDELIVERED -> ActivityOutcome.Undelivered(settled)
        else -> ActivityOutcome.Unclaimed(settled)
      }
      return ActivityEntry(
        id = id,
        at = at,
        runtimeStart = runtimeStart,
        origin = origin,
        workspace = workspace,
        tool = tool,
        arguments = arguments,
        // A Lost Operation produced no result, so it has no elapsed time to report.
        elapsed = completedAt?.let { (it.toEpochMilli() - at.toEpochMilli()).milliseconds },
        outcome = outcome,
        deliveries = deliveries,
        acknowledgedAt = acknowledgedAt,
      )
    }
  }

  private class Record(val kind: String, val fields: Map<String, String>)

  /** One line of the account: the bytes as they were written, and the record if this version knows the kind. */
  private class Line(val raw: String, val record: Record?)

  /**
   * One record to a line: the kind, then `name=value` fields, tab-separated. Line-oriented
   * because appending one whole line is the cheapest durable write there is, and because the
   * account stays readable with `tail` when someone is looking at a machine that will not start.
   */
  private fun encode(record: Record): String =
    (listOf(record.kind) + record.fields.map { (name, value) -> "$name=${escape(value)}" }).joinToString("\t")

  private fun decode(line: String): Record? {
    val parts = line.split('\t')
    val kind = parts.firstOrNull()?.takeIf { it in KINDS } ?: return null
    val fields = parts.drop(1).mapNotNull { field ->
      val separator = field.indexOf('=')
      if (separator < 0) null else field.take(separator) to unescape(field.substring(separator + 1))
    }
    return Record(kind, fields.toMap())
  }

  private fun escape(value: String): String = buildString(value.length) {
    for (character in value) when (character) {
      '\\' -> append("\\\\")
      '\t' -> append("\\t")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      else -> append(character)
    }
  }

  private fun unescape(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
      val character = value[index++]
      if (character != '\\' || index == value.length) {
        append(character)
        continue
      }
      when (val escaped = value[index++]) {
        't' -> append('\t')
        'n' -> append('\n')
        'r' -> append('\r')
        else -> append(escaped)
      }
    }
  }

  companion object {
    private const val NEWLINE: Byte = '\n'.code.toByte()

    /** 30 days. A tunable, which is how a test asks a question about expiry in a second. */
    val DEFAULT_RETENTION: Duration = 30.days

    /** How often a Runtime that never restarts compacts anyway. */
    private val PRUNE_INTERVAL: Duration = 1.hours

    private const val OPEN = "open"
    private const val COMPLETE = "complete"
    private const val UNDELIVERED = "undelivered"
    private const val UNCLAIMED = "unclaimed"
    private const val DELIVERY = "delivery"
    private const val ACK = "ack"
    private val KINDS = setOf(OPEN, COMPLETE, UNDELIVERED, UNCLAIMED, DELIVERY, ACK)

    private const val ID = "id"
    private const val AT = "at"
    private const val START = "start"
    private const val ORIGIN = "origin"
    private const val WORKSPACE = "workspace"
    private const val TOOL = "tool"
    private const val ARGUMENTS = "args"
    private const val OUTCOME = "outcome"
    private const val DETAIL = "detail"
    private const val CARRIED = "carried"

    private const val OK = "ok"
    private const val FAILED = "failed"
    private const val UNCERTAIN = "uncertain"
  }
}
