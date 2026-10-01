import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
  id("proxenos.kotlin-library")
  id("proxenos.compose-resources")
  distribution
}

dependencies {
  // Frontends use the domain interfaces and the control socket, never construct the core.
  implementation(project(":core-api"))
  implementation(project(":control"))
  implementation(project(":frontend"))
  // Desktop rendering and the confirmation/progress/snackbar widgets; no jpackage image.
  implementation(libs.compose.desktop)
  implementation(libs.compose.material3)
  implementation(libs.compose.components.resources)
  // The portal's live light/dark (GUI-SPEC §8).
  implementation(libs.dbus.java.core)
  implementation(libs.dbus.java.unixsocket)
  runtimeOnly(libs.slf4j.nop)
  // Owner intents run against a real core/socket. Test-only; main's boundaries stay unchanged.
  testImplementation(project(":core"))
  testImplementation(libs.compose.ui.test)
}

compose.resources {
  packageOfResClass = "io.github.kzagoris.proxenos.gui.res"
}

moduleBoundaries {
  mayReach(":core-api", ":control", ":frontend")
}

// Compose resolves two runtime-saveable jars with the same filename, from different groups.
// Preserve both in lib/ and give the launcher exactly the names installed there.
val runtimeJars = configurations.runtimeClasspath.map { configuration ->
  val artifacts = configuration.resolvedConfiguration.resolvedArtifacts
  val collisions = artifacts.groupBy { it.file.name }.filterValues { it.size > 1 }.keys
  artifacts.associate { artifact ->
    artifact.file to if (artifact.file.name in collisions)
      "${artifact.moduleVersion.id.group}-${artifact.file.name}" else artifact.file.name
  }
}

val startScripts = tasks.register<CreateStartScripts>("startScripts") {
  dependsOn(tasks.jar)
  applicationName = "gui"
  mainClass = "io.github.kzagoris.proxenos.gui.MainKt"
  outputDir = layout.buildDirectory.dir("scripts").get().asFile
  classpath = files(tasks.jar, runtimeJars.map { jars -> jars.values.map { file(it) } })
  defaultJvmOpts = listOf("--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
  doLast {
    val invocation = "exec \"\$JAVACMD\" \"\$@\""
    val script = unixScript.readText()
    check(invocation in script) { "The Unix launcher no longer invokes the JVM as expected" }
    unixScript.writeText(script.replace(invocation,
      "export SKIKO_RENDER_API=\${SKIKO_RENDER_API:-SOFTWARE_FAST}\n" +
        "exec \"\$JAVACMD\" \"-Dproxenos.runtime=\$APP_HOME/bin/runtime\" \"\$@\""))
  }
}

distributions {
  main {
    contents {
      into("bin") {
        from(startScripts) { exclude("*.bat") }
        filePermissions { unix("rwxr-xr-x") }
      }
      into("lib") {
        from(tasks.jar)
        val installedJars = runtimeJars.get()
        from(configurations.runtimeClasspath) {
          eachFile { name = installedJars.getValue(file) }
        }
      }
    }
  }
}

tasks.test {
  environment.remove("DISPLAY")
  environment.remove("WAYLAND_DISPLAY")
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
