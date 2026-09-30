package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent

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
  operator fun contains(entry: ActivityEntryId): Boolean = entries.any { it.id == entry }
}

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
