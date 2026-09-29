plugins {
  base
  // For jlink: the bundled distribution's runtime is cut from the same JDK 26 toolchain the code
  // is compiled with, so it is provisioned the same way (SPEC §12) and never the launching JVM.
  `jvm-toolchains`
  // The one thing a user installs (SPEC §12): `runtime` and `tui` side by side in one
  // tree, plus the scripts and documents first run needs. `./gradlew installDist` lays it out in
  // build/install/proxenos/; `distTar` and `distZip` pack the same tree.
  distribution
}

// build-logic's own tests are the guardrail's guarantee: they prove checkModuleBoundaries
// actually fails a build that reaches too far. An included build runs only the tasks the root
// needs, so without this line they would never run.
tasks.named("build") {
  dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

distributions {
  main {
    distributionBaseName = "proxenos"
    contents {
      // Both applications' own installations, merged: bin/runtime and bin/tui over one lib/.
      // One tree is the point, not a convenience — the TUI starts the Runtime it finds at
      // bin/runtime beside its own launcher (RuntimeLauncher.executableFrom). The two lib/
      // directories overlap in every shared jar, and the copies are the same file.
      // Named by path, because the root is configured before either project has a task to
      // look up; builtBy is what still makes installing here install them first.
      from(files("runtime/build/install/runtime").builtBy(":runtime:installDist"))
      from(files("tui/build/install/tui").builtBy(":tui:installDist"))
      duplicatesStrategy = DuplicatesStrategy.EXCLUDE
      // Linux-first (SPEC §12): a Windows launcher would promise a port that does not exist.
      exclude("bin/*.bat")
      // An archive does not carry a file's mode from disk, so a launcher packed without this
      // unpacks as a text file that `Permission denied`s. This reaches the two installations'
      // launchers. It also runs over the child spec below, but there a script's path is relative
      // to that spec (`wizard`, not `bin/wizard`), so the pattern misses it and it sets its own.
      filesMatching("bin/*") {
        permissions { unix("rwxr-xr-x") }
      }

      into("bin") {
        filePermissions { unix("rwxr-xr-x") }
        from("scripts/wizard")
        from("scripts/install-tunnel-client") {
          // The installer downloads the tunnel-client release the catalog pins, so the version
          // lives in one place and the shipped script cannot disagree with it.
          val tunnelClient = libs.versions.tunnel.client.get()
          filter { line -> line.replace("@TUNNEL_CLIENT_VERSION@", tunnelClient) }
        }
      }
      into("docs") {
        // Under docs/ as in the repository, so its relative links hold in both places.
        from("docs/INSTALL.md")
        // Documentation, not an installer: nothing in the product copies or enables this unit
        // (SPEC §11.4). It ships so a user who reads the reason and disagrees has it to hand.
        into("systemd") {
          from("docs/systemd")
        }
      }
    }
  }
}

// A release names its archives by version: the workflow passes -Pversion, and a local build that
// passes nothing says so rather than posing as a release.
if (version == Project.DEFAULT_VERSION) version = "0.0.0-dev"

// tar.gz only: Linux-only (SPEC §12), and a zip does not keep a launcher's mode.
tasks.withType<Tar>().configureEach {
  compression = Compression.GZIP
  archiveExtension = "tar.gz"
}
tasks.withType<Zip>().configureEach { enabled = false }

// The bundled distribution (docs/adr/0010-a-release-bundles-its-java-runtime.md): the same tree
// plus a trimmed Java runtime in jre/, for linux-x64, so a user needs no JDK 26 of their own.
// The module list is fixed rather than computed: jdeps over non-modular Kotlin jars is not to be
// trusted, and a missing module fails the smoke test the release runs against this tree.
val jreModules = listOf(
  "java.base", "java.desktop", "java.instrument", "java.logging", "java.management",
  "jdk.net", "jdk.unsupported",
)
val jlinkOutput = layout.buildDirectory.dir("jlink/jre")
val jlink by tasks.registering(Exec::class) {
  val jlinkExecutable = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
  }.map { it.metadata.installationPath.file("bin/jlink").asFile.path }
  val output = jlinkOutput.map { it.asFile }
  inputs.property("modules", jreModules)
  outputs.dir(jlinkOutput)
  doFirst { output.get().deleteRecursively() }
  executable(jlinkExecutable.get())
  args(
    "--add-modules", jreModules.joinToString(","),
    "--strip-debug", "--no-header-files", "--no-man-pages",
    "--compress", "zip-6",
    "--output", output.get().path,
  )
}

distributions {
  create("bundled") {
    distributionBaseName = "proxenos"
    distributionClassifier = "linux-x64"
    contents {
      with(distributions["main"].contents)
      into("jre") {
        val executables = listOf("bin/*", "lib/jspawnhelper")
        from(jlink) { exclude(executables) }
        // As with the launchers, the archive keeps no mode from disk; jspawnhelper is how the
        // JDK starts every child process, so without it no Command or Git tool could run.
        from(jlink) {
          include(executables)
          filePermissions { unix("rwxr-xr-x") }
        }
      }
      // The start scripts fall back to JAVA_HOME, else `java` on PATH. Here they use the tree's
      // own runtime unconditionally: a stray JAVA_HOME pointing at an older Java would otherwise
      // fail with UnsupportedClassVersionError in an archive that promised to need no Java.
      filesMatching(listOf("bin/runtime", "bin/tui")) {
        filter { line ->
          if (line == "if [ -n \"\$JAVA_HOME\" ] ; then") "JAVA_HOME=\$APP_HOME/jre\n$line" else line
        }
      }
    }
  }
}
