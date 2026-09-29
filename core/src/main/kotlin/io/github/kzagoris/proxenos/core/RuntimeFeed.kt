package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.RuntimeStatus
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What `observe()` streams (SPEC §9): the Runtime's state as a frontend sees it, kept here as
 * its own copy and moved on by every [RuntimeEvent.Change] the registry, Activity and the tunnel
 * publish.
 *
 * **Its own copy, on purpose.** A snapshot assembled by asking the registry would take the
 * registry's lock from inside this one, while the registry publishes from inside its own — two
 * locks taken in both orders. Holding the state here means a publisher takes its lock and then
 * this one, and nothing here ever calls out.
 *
 * **Nothing is lost between the snapshot and the first change**, because they are one step:
 * the snapshot is taken and the subscriber added under the lock every publish takes. A publisher
 * changes its own state first and publishes second, so a change landing in that window can at
 * worst arrive both inside the snapshot and after it — and every change names the whole of what
 * it changes, so applying it twice is harmless.
 */
class RuntimeFeed {
  private val lock = Any()
  // Seeded with the tunnel's real status by RuntimeManagement before any frontend can attach.
  // The catalog is here from the start and never changes, so no change ever carries it.
  private var state = RuntimeEvent.Snapshot(
    emptyList(), RuntimeStatus(RuntimeState.Connecting, Instant.now()), emptyList(), catalog = OperationCatalog.ENTRIES,
  )
  // Unbounded, like the tunnel's transitions: a slow frontend sees each change late rather than
  // missing one, and none of them can hold up the publisher — the registry publishes under its
  // own lock, and a frontend stalled on a tty must not stall a SetLevel on another.
  private val subscribers = mutableListOf<Channel<RuntimeEvent>>()

  fun publish(change: RuntimeEvent.Change) = synchronized(lock) {
    state = state.after(change)
    subscribers.forEach { it.trySend(change) }
  }

  fun observe(): Flow<RuntimeEvent> = flow {
    val subscriber = Channel<RuntimeEvent>(Channel.UNLIMITED)
    synchronized(lock) {
      subscriber.trySend(state)
      subscribers += subscriber
    }
    try {
      for (event in subscriber) emit(event)
    } finally {
      synchronized(lock) { subscribers -= subscriber }
    }
  }
}
