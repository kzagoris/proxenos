// Compose resources for a module that ships its own icons: `gui` (GUI-SPEC §6). The Compose
// Gradle plugin is what generates the `Res` accessors; without it components-resources compiles
// to nothing a module can name. Applied without a `compose.desktop.application` block, so it adds
// no `run` task to clash with the launcher the module builds itself. The plugin also registers its
// bundled hot-reload tasks; they run only when named and put nothing on the runtime classpath, so
// hot reload never applies to bin/gui (GUI-SPEC §10.1).
plugins {
  id("proxenos.compose")
  id("org.jetbrains.compose")
}
