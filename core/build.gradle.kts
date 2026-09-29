plugins {
  id("proxenos.kotlin-library")
}

dependencies {
  // api, not implementation: core implements the interfaces core-api declares, and every
  // consumer of core holds those types. Hiding them would only mean each consumer
  // re-declaring the same dependency.
  api(project(":core-api"))
  // Not api: the core's blocking work moves to an I/O dispatcher inside it (SPEC §6.6), which
  // is a promise about `perform`, not a type a caller has to hold.
  implementation(libs.coroutines.core)
  // The Connected signal (SPEC §8.4): /metrics read from the tunnel child over a Unix socket,
  // which only the CIO engine can dial, and the child's JSON log lines read for their words.
  implementation(libs.ktor.client.cio)
  implementation(libs.serialization.json)

  // The fake /metrics responder the Connected state machine is driven against (SPEC §13.1).
  testImplementation(libs.ktor.server.cio)
}

moduleBoundaries {
  mayReach(":core-api")
}
