package io.github.kzagoris.proxenos.coreapi

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An [Instant] as ISO-8601 text, the one domain type kotlinx.serialization has no serializer
 * for. Text rather than epoch millis: Activity writes the same form to disk, and nanoseconds
 * survive the round trip where millis would round an `enteredAt` off.
 */
object InstantSerializer : KSerializer<Instant> {
  override val descriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
  override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
