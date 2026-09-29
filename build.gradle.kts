plugins {
  base
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
