plugins {
  id("proxenos.kotlin-library")
}

dependencies {
  // The adapter is a caller of the core, and nothing calls back into it: implementation, so
  // core does not leak into anyone who later depends on mcp.
  implementation(project(":core"))
  implementation(libs.mcp.server)
  // kotlin-sdk-server ships no engine (SPEC §12), so the adapter brings its own. CIO is the
  // only Ktor engine that can serve a Unix socket.
  implementation(libs.ktor.server.cio)

  // The transport, tested as a transport: an in-process MCP client over the socket the
  // adapter bound. Test-only, so nothing the Runtime ships carries an MCP client.
  testImplementation(libs.mcp.client)
  testImplementation(libs.ktor.client.cio)
  testImplementation(libs.coroutines.core)
}

moduleBoundaries {
  // :core-api arrives through :core's api. Transitive arrivals are declared, not assumed.
  mayReach(":core", ":core-api")
}
