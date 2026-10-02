package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.RunningCommand
import io.github.kzagoris.proxenos.coreapi.RunningOperation
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import kotlin.time.Duration

/** The name `run_command` goes by in Activity and in the catalog. */
const val RUN_COMMAND = "run_command"

/** The name `get_result` goes by in Activity and in the catalog. */
const val GET_RESULT = "get_result"

/**
 * This Runtime start's entries, oldest first: the feed a frontend shows. The snapshot keeps
 * earlier starts' entries for the account; a frontend shows the current start only.
 */
val RuntimeEvent.Snapshot.feed: List<ActivityEntry>
  get() {
    val current = start?.id ?: return emptyList()
    return activity.filter { it.runtimeStart == current }
  }

/** One row of the feed as drawn: an entry, or a run of `get_result` polls of one Handle folded into one. */
data class FeedRow(val entries: List<ActivityEntry>) {
  val id: ActivityEntryId get() = entries.last().id
  /** What a frontend keys the drawn row by: the first poll's, which stays put as later polls fold in. */
  val key: ActivityEntryId get() = entries.first().id
  operator fun contains(entry: ActivityEntryId): Boolean = entries.any { it.id == entry }

  /** The entry the row's detail is about: the one in it still waiting to be Acknowledged, else its newest. */
  val detailed: ActivityEntry get() = entries.firstOrNull { it.needsAttention } ?: entries.last()
}

/**
 * The command line a running `run_command` was given: from what it has said once that has been
 * read, and until then from the arguments its entry was opened with.
 */
fun commandOf(running: RunningOperation, output: RunningCommand?): String =
  output?.command ?: running.arguments.removePrefix("command=").substringBeforeLast(" cwd=")

/** The last line a running command printed, or null while it has printed nothing. */
val RunningCommand.lastLine: String? get() = outputSoFar.trimEnd().substringAfterLast('\n').trim().ifEmpty { null }

/** How long something has been running, as the band and the stop confirmation say it: `42s`, `3m05s`, `2h01m`. */
fun runningFor(seconds: Long): String {
  val whole = seconds.coerceAtLeast(0)
  return when {
    whole < 60 -> "${whole}s"
    whole < 3600 -> "%dm%02ds".format(whole / 60, whole % 60)
    else -> "%dh%02dm".format(whole / 3600, whole % 3600 / 60)
  }
}

/** How long a finished Operation took: `120ms`, `1.2s`. */
fun took(elapsed: Duration): String =
  elapsed.inWholeMilliseconds.let { ms -> if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0) }

/**
 * [entries] as rows, in the same order: consecutive `get_result` polls of one Handle fold into
 * one counted row — a display fold, never an edit, so every poll is still in [entries]. Only
 * polls that agree fold — same Workspace, same Handle, same outcome, same surface, same Runtime
 * start — so a poll the lowered level refused breaks the run rather than hiding inside it.
 */
fun folded(entries: List<ActivityEntry>): List<FeedRow> {
  val rows = mutableListOf<MutableList<ActivityEntry>>()
  for (entry in entries) {
    val last = rows.lastOrNull()?.last()
    val same = last != null && entry.tool == GET_RESULT && last.tool == GET_RESULT &&
      entry.workspace == last.workspace && entry.arguments == last.arguments && entry.origin == last.origin &&
      entry.runtimeStart == last.runtimeStart && entry.outcome.said == last.outcome.said
    if (same) rows.last() += entry else rows += mutableListOf(entry)
  }
  return rows.map { FeedRow(it) }
}
