plugins {
  id("proxenos.kotlin-library")
  id("proxenos.serialization")
}

dependencies {
  // api: the management client's whole point is to hand back WorkspaceManagement, which is
  // declared in core-api.
  //
  // And core-api is the only project here. The control socket is what a frontend
  // talks to, so control must not be able to construct the core and shortcut it. Add
  // project(":core") and checkModuleBoundaries fails the build — that guarantee is the
  // reason core-api exists at all.
  api(project(":core-api"))
  // The wire format: one JSON document per line. core-api's types say how they serialize;
  // which format carries them is this module's choice alone.
  implementation(libs.serialization.json)

  // The named harness: the management client driven against an in-process core, so
  // the two implementations are proven interchangeable. Test-only — the boundary check polices
  // main classpaths, and main here still cannot reach the core.
  testImplementation(project(":core"))
}

moduleBoundaries {
  mayReach(":core-api")
}
