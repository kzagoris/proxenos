// A library that also has a main(): `runtime` and `tui`.
plugins {
  id("proxenos.kotlin-library")
  application
}

// A launcher's JVM options may name a file in its own tree as `__APP_HOME__/...`. The start
// script escapes every shell expansion in its options, so the placeholder is swapped for the
// script's own APP_HOME after it is written; APP_HOME is resolved through any symlink to the
// launcher. The Unix script only: nothing ships a Windows one.
tasks.named<CreateStartScripts>("startScripts") {
  doLast {
    unixScript.writeText(unixScript.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
  }
}
