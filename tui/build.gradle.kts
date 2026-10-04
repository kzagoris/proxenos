plugins {
  id("proxenos.kotlin-library")
  application
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
  // What every frontend must agree on — attaching, starting the Runtime, and the domain's
  // wording — so this one cannot drift from the others.
  implementation(project(":frontend"))
  implementation(libs.mosaic.runtime)

  // Two TUIs attached at once are proven against a real core on a real control socket, as
  // control's own harness does. Test-only: the boundary check polices main, which still
  // cannot reach the core.
  testImplementation(project(":core"))
  // Cross-frontend acceptance: a TUI and two GUI owners observe one real Runtime.
  testImplementation(project(":gui"))
}

application {
  mainClass = "io.github.kzagoris.proxenos.tui.MainKt"
  // Mosaic's tty library loads a native library of its own. Without this every start prints a
  // four-line JDK warning onto the terminal the screen is about to draw on.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.startScripts {
  // The Runtime this launcher starts is the bin/runtime of its own tree. APP_HOME is resolved
  // through any symlink to the launcher, so a linked bin/tui still finds it. Pass the property
  // as one JVM argument: DEFAULT_JVM_OPTS is re-parsed by xargs, which consumes quotes and
  // backslashes in an installation's name.
  doLast {
    val invocation = "exec \"\$JAVACMD\" \"\$@\""
    val script = unixScript.readText()
    check(invocation in script) { "The Unix launcher no longer invokes the JVM as expected" }
    unixScript.writeText(script.replace(invocation, "exec \"\$JAVACMD\" \"-Dproxenos.runtime=\$APP_HOME/bin/runtime\" \"\$@\""))
  }
}

moduleBoundaries {
  mayReach(":core-api", ":control", ":frontend")
}
