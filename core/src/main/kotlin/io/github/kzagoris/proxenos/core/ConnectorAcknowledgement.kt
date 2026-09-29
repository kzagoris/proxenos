package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * What this machine keeps about the connector in ChatGPT (SPEC §11.5, ADR 0007), which it cannot
 * see at all: the catalog fingerprint the user last said their connector was built against — the
 * one persisted fact in the design about something outside this machine — compared with the
 * [fingerprint] of the catalog this Runtime serves.
 *
 * **Unconfirmed is derived, never stored.** Absent and different are the same judgement, so a
 * fresh install and an upgrade whose catalog differs are one code path and one banner. The
 * fingerprint is of the catalog data the core owns and nothing else, so no registration and no
 * Access Level can reach it, and a restart can never raise it by itself.
 *
 * Nothing here verifies that a connector exists: [acknowledge] records the user's word.
 */
class ConnectorAcknowledgement(
  private val file: Path,
  private val feed: RuntimeFeed = RuntimeFeed(),
  val fingerprint: String = catalogFingerprint(OperationCatalog.ENTRIES),
) {
  private val lock = Any()
  private var unconfirmed = read() != fingerprint

  init {
    publish()
  }

  /** Stores the current fingerprint, written whole or not at all, and durably: a lost one re-raises the banner. */
  fun acknowledge(): Unit = synchronized(lock) {
    writeDurably(file, fingerprint + "\n")
    unconfirmed = false
    publish()
  }

  private fun publish() = feed.publish(RuntimeEvent.Change.ConnectorChanged(unconfirmed))

  /**
   * Whatever the file holds, unparsed: anything but the current fingerprint reads as Unconfirmed.
   * So does a file that cannot be read at all — a confirmation nobody can read is not one, and
   * refusing to start over it would make the banner the least of the user's problems.
   */
  private fun read(): String? = try {
    Files.readString(file).trim()
  } catch (_: IOException) {
    null
  }
}
