package io.github.kzagoris.proxenos.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutations serialize on the target's resolved real path (SPEC §6.6). The key is the real path
 * and not the Workspace precisely because the access rules permit nested and overlapping Roots:
 * two Workspaces reaching one file must contend, and per-Workspace serialization would let them
 * past each other.
 *
 * Entries are held only while somebody wants them, so a Runtime that has written a million files
 * does not carry a million mutexes.
 */
internal class PathLocks {
  private val held = mutableMapOf<String, Entry>()

  suspend fun <R> withPathLock(key: String, block: suspend () -> R): R {
    val entry = synchronized(held) { held.getOrPut(key) { Entry() }.also { it.wanted++ } }
    try {
      return entry.mutex.withLock { block() }
    } finally {
      synchronized(held) { if (--entry.wanted == 0) held.remove(key) }
    }
  }

  /** Nothing outside a test reads this; it is how "the map does not grow" stays observable. */
  fun heldKeys(): Set<String> = synchronized(held) { held.keys.toSet() }

  private class Entry(val mutex: Mutex = Mutex(), var wanted: Int = 0)
}
