plugins {
  id("proxenos.kotlin-application")
}

dependencies {
  // The composition root, and the only place that is allowed to see everything: it builds the
  // core, hands one Origin-stamped view to mcp and another to the control socket, and owns
  // the tunnel child.
  implementation(project(":core"))
  implementation(project(":mcp"))
  implementation(project(":control"))
  // The shutdown hook ends running Operations through the core's suspending Stop.
  implementation(libs.coroutines.core)
}

application {
  mainClass = "io.github.kzagoris.proxenos.runtime.MainKt"
}

tasks.test {
  // TunnelClientInstallTest runs the shipped installer as a user would. Declared as an input, so
  // a change to the script re-runs the tests rather than restoring them from the build cache.
  val installer = layout.settingsDirectory.file("scripts/install-tunnel-client")
  inputs.file(installer).withPropertyName("installer")
  systemProperty("installer", installer.asFile.path)
}

moduleBoundaries {
  mayReach(":core-api", ":core", ":mcp", ":control")
}
