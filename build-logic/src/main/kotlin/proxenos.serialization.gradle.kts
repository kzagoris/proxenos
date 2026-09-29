// @Serializable for the modules whose types cross the control socket: core-api, whose domain
// types are the wire format, and control, whose frames wrap them (SPEC §9).
//
// The compiler plugin ships in the Kotlin release, so its version is the Kotlin version — the
// same pairing the Compose plugin has, kept here for the same reason. Left out, @Serializable
// compiles to nothing and `serializer()` fails at run time rather than at build time.
plugins {
  id("org.jetbrains.kotlin.plugin.serialization")
}
