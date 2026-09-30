plugins {
  id("proxenos.kotlin-application")
  // Drop this and the failure is not "missing plugin" but a back-end inlining crash inside
  // `remember`, which is unrecognisable the first time.
  id("proxenos.compose")
}

dependencies {
  // A frontend sees the interfaces and the client that implements them over the control
  // socket. Not the core: a frontend that could construct one would hold a core that dies
  // with it, which is the wrong lifetime.
  implementation(project(":core-api"))
  implementation(project(":control"))
  implementation(libs.mosaic.runtime)

  // Two TUIs attached at once are proven against a real core on a real control socket, as
  // control's own harness does. Test-only: the boundary check polices main, which still
  // cannot reach the core.
  testImplementation(project(":core"))
}

application {
  mainClass = "io.github.kzagoris.proxenos.tui.MainKt"
  // Mosaic's tty library loads a native library of its own. Without this every start prints a
  // four-line JDK warning onto the terminal the screen is about to draw on.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

moduleBoundaries {
  mayReach(":core-api", ":control")
}
