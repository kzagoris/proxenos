package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.frontend.Attachment

/**
 * One event as the attachment hands it to the screen: already folded onto the snapshot shown,
 * so a test can state a change rather than the whole Runtime after it.
 */
internal fun Home.observed(event: RuntimeEvent): Home = when (event) {
  is RuntimeEvent.Snapshot -> observed(Attachment.Attached(event))
  is RuntimeEvent.Change -> snapshot?.let { observed(Attachment.Attached(it.after(event))) } ?: this
}
