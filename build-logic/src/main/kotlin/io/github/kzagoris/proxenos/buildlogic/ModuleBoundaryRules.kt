package io.github.kzagoris.proxenos.buildlogic

/**
 * The comparison behind the guardrail, kept away from Gradle's types so it can be read and
 * tested on its own.
 *
 * [reached] is everything the module can see on a classpath, transitively — not only what it
 * declared. That is the point: `core-api` exists so a frontend cannot construct the core and
 * bypass the control socket, and a direct-dependency check would miss a `core` that arrived
 * through someone else's `api`.
 */
internal fun violations(
  subject: String,
  reached: Set<String>,
  allowed: Set<String>,
): List<String> = (reached - allowed - subject).sorted()

internal fun boundaryReport(
  subject: String,
  violations: List<String>,
  reached: Set<String>,
  allowed: Set<String>,
): String = buildString {
  appendLine("$subject reaches ${violations.joinToString(", ")}, which it does not declare.")
  appendLine()
  appendLine("  reached: ${render(reached - subject)}")
  appendLine("  mayReach: ${render(allowed)}")
  appendLine()
  append(
    "Everything on $subject's compile and runtime classpath has to be named in a " +
      "moduleBoundaries { mayReach(...) } block, transitive arrivals included. If the " +
      "dependency is wanted, declare it there and say why in SPEC §1; if it is not, the " +
      "build has just caught what discipline would not have.",
  )
}

private fun render(paths: Set<String>): String =
  if (paths.isEmpty()) "nothing" else paths.sorted().joinToString(", ")
