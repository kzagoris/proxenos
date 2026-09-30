plugins {
  id("proxenos.kotlin-library")
}

dependencies {
  // What both frontends must agree on, and nothing either one draws with: no Compose and no
  // Mosaic here, so a frontend takes this without taking the other's toolkit.
  //
  // api: an Attachment carries the Runtime's Snapshot, and management is a WorkspaceManagement.
  api(project(":core-api"))
  // The management client and the control socket's transport failures. Not the core: attaching
  // is dialling a Runtime, never constructing one.
  implementation(project(":control"))

  // Attaching is proven against a real core on a real control socket, as control's own harness
  // does. Test-only: the boundary check polices main, which still cannot reach the core.
  testImplementation(project(":core"))
}

moduleBoundaries {
  mayReach(":core-api", ":control")
}
