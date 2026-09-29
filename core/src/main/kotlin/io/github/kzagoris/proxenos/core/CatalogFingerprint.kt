package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.OperationSpec
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.text.Charsets.UTF_8

/**
 * The catalog, hashed as the data the core owns (SPEC §11.5, ADR 0007). ChatGPT snapshots the
 * catalog when the connector is created and never refreshes it, so a build whose catalog hashes
 * differently from the one the user last confirmed is a build their connector does not match.
 *
 * Only the catalog goes in, entry by entry and field by field — never a build number, a date or
 * a Runtime start — so an upgrade that changes no entry leaves the connector confirmed, and a
 * restart can never raise a banner by itself.
 *
 * Every field is length-prefixed. Joining them with a separator instead would let text moving
 * from one field into its neighbour hash the same, and a fingerprint that misses a change is
 * the one failure this exists to prevent.
 */
fun catalogFingerprint(catalog: List<OperationSpec>): String {
  val digest = MessageDigest.getInstance("SHA-256")
  fun field(value: String) {
    val bytes = value.toByteArray(UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    digest.update(bytes)
  }
  field(catalog.size.toString())
  for (spec in catalog) {
    field(spec.name)
    field(spec.description)
    field(spec.requiredLevel?.name.orEmpty())
    field(spec.arguments.size.toString())
    for (argument in spec.arguments) {
      field(argument.name)
      field(argument.type.name)
      field(argument.required.toString())
      field(argument.description)
    }
  }
  return HexFormat.of().formatHex(digest.digest())
}
