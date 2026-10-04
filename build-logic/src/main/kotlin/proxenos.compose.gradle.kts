// Compose compiler for the TUI and GUI frontends.
//
// Mosaic publishes no Gradle plugin after 0.12.0, so the Compose compiler plugin is
// applied by hand — and it is part of the Kotlin release, so its version is the Kotlin version.
// Keeping the pairing here rather than in a module's build file is what stops the two drifting.
// Left out, the failure is not "missing plugin" but an inlining crash inside `remember`.
plugins {
  id("org.jetbrains.kotlin.plugin.compose")
}
