plugins {
  id("proxenos.kotlin-library")
  id("proxenos.serialization")
}

dependencies {
  // The domain types are the control protocol's wire format (SPEC §9): request-shaped acts are
  // serialize-send-deserialize, which only holds if the acts themselves serialize. Annotations
  // and serializers only — which format goes on the wire is control's choice, not this one's.
  api(libs.serialization.core)
  // WorkspaceManagement.observe() is a Flow.
  api(libs.coroutines.core)
}

// The bottom of the graph, and it stays there. core-api is what a frontend is given —
// interfaces and domain types, nothing that can open a file or start a process — so that
// "could a conversation raise an Access Level?" is answered by the compiler (SPEC §9).
moduleBoundaries {
  // Reaches nothing on purpose.
}
