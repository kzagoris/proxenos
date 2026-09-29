package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.ArgumentType
import kotlin.test.*

class CatalogFingerprintTest {
  /**
   * A restart rebuilds the catalog from scratch, so what has to hold across one is that equal
   * data hashes equally — not that the same objects do. A deep copy is that rebuild without the
   * restart.
   */
  @Test
  fun `an unchanged catalog fingerprints the same when it is rebuilt`() {
    val rebuilt = OperationCatalog.ENTRIES.map { spec -> spec.copy(arguments = spec.arguments.map { it.copy() }) }
    assertEquals(catalogFingerprint(OperationCatalog.ENTRIES), catalogFingerprint(rebuilt))
  }

  /** Each of these is a change ChatGPT's frozen snapshot would not know about. */
  @Test
  fun `changing any one thing about one entry changes the fingerprint`() {
    val original = catalogFingerprint(OperationCatalog.ENTRIES)
    val entry = OperationCatalog.ENTRIES.first { it.arguments.isNotEmpty() }
    val argument = entry.arguments.first()
    val variants = listOf(
      entry.copy(description = entry.description + " "),
      entry.copy(name = entry.name + "_v2"),
      entry.copy(requiredLevel = null),
      entry.copy(arguments = entry.arguments.drop(1)),
      entry.copy(arguments = listOf(argument.copy(required = !argument.required)) + entry.arguments.drop(1)),
      entry.copy(arguments = listOf(argument.copy(type = ArgumentType.Flag)) + entry.arguments.drop(1)),
      entry.copy(arguments = listOf(argument.copy(description = "")) + entry.arguments.drop(1)),
    )
    for (variant in variants) {
      val changed = OperationCatalog.ENTRIES.map { if (it === entry) variant else it }
      assertNotEquals(original, catalogFingerprint(changed), "unnoticed: $variant")
    }
  }

  /** Text moved from one field to its neighbour is a different catalog, not the same bytes. */
  @Test
  fun `field boundaries are part of what is hashed`() {
    val entry = OperationCatalog.ENTRIES.first()
    val shifted = entry.copy(name = entry.name + entry.description.take(1), description = entry.description.drop(1))
    assertNotEquals(catalogFingerprint(listOf(entry)), catalogFingerprint(listOf(shifted)))
  }
}
