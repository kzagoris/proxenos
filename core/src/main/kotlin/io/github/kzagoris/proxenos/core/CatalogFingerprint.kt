package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.github.kzagoris.proxenos.coreapi.ArgumentSpec
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.text.Charsets.UTF_8

/**
 * The catalog, hashed as the data the core owns (ADR 0007). ChatGPT snapshots the
 * catalog until it is explicitly refreshed or recreated (ADR 0014), so a different fingerprint
 * needs the user's acknowledgement that the connector's tool list was updated.
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
  fun argument(value: ArgumentSpec) {
    field(value.name)
    field(value.type.name)
    field(value.required.toString())
    field(value.description)
    field(value.nullable.toString())
    field(value.reference.orEmpty())
    field(value.definitions.size.toString())
    value.definitions.forEach(::argument)
    field(value.minimum.toString())
    field(value.maximum.toString())
    field(value.minItems.toString())
    field(value.maxItems.toString())
    field(value.enumValues.size.toString())
    value.enumValues.forEach(::field)
    field((value.items != null).toString())
    value.items?.let(::argument)
    field(value.properties.size.toString())
    value.properties.forEach(::argument)
    field(value.alternatives.size.toString())
    value.alternatives.forEach(::argument)
  }
  field(catalog.size.toString())
  for (spec in catalog) {
    field(spec.name)
    field(spec.description)
    field(spec.requiredLevel?.name.orEmpty())
    field(spec.annotations.readOnly.toString())
    field(spec.annotations.destructive.toString())
    field(spec.annotations.openWorld.toString())
    field(spec.arguments.size.toString())
    spec.arguments.forEach(::argument)
    field((spec.output != null).toString())
    spec.output?.let(::argument)
  }
  return HexFormat.of().formatHex(digest.digest())
}
