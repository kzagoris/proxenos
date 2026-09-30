package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.core.CoreArtifact

/**
 * The adapter's name, and the one edge the design draws from it. [McpEndpoint] is what the
 * artifact actually is.
 */
object McpArtifact {
  const val NAME: String = "mcp"

  /**
   * Nothing reads this, and that is not an oversight: referencing the artifact above is what
   * makes the edge the design draws a compiled fact rather than a line in a build file, and it
   * gives checkModuleBoundaries something real to police.
   */
  val reaches: List<String> = listOf(CoreArtifact.NAME)
}
