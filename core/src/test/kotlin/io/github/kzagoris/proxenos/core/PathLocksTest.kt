package io.github.kzagoris.proxenos.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathLocksTest {
  @Test
  fun `one real path serializes, a different one does not, and neither is kept afterwards`() = runBlocking<Unit> {
    val locks = PathLocks()
    val holding = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val second = CompletableDeferred<Unit>()

    val first = launch(Dispatchers.Default) {
      locks.withPathLock("/one/file") { holding.complete(Unit); release.await() }
    }
    holding.await()
    val reached = CompletableDeferred<Unit>()
    val contending = launch(Dispatchers.Default) {
      reached.complete(Unit)
      locks.withPathLock("/one/file") { second.complete(Unit) }
    }
    reached.await()
    // A mutation on another real path is not held up by this one.
    launch(Dispatchers.Default) { locks.withPathLock("/another/file") { } }.join()
    // Waiting rather than sampling: without the lock the second mutation lands immediately,
    // so an assertion that merely looked now could pass before it had even started.
    assertNull(
      withTimeoutOrNull(500) { second.await() },
      "two mutations on one real path must not overlap",
    )

    release.complete(Unit)
    listOf(first, contending).joinAll()
    assertTrue(second.isCompleted)
    assertEquals(emptySet(), locks.heldKeys())
  }
}
