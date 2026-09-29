package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.CoreApiArtifact

/**
 * Placeholder. The registry, the admission pipeline, the adapters, Activity and supervision
 * arrive with SPEC §14 items 3-8, 10.
 */
object CoreArtifact {
  const val NAME: String = "core"

  /**
   * Nothing reads this, and that is not an oversight: referencing the artifact above is what
   * makes the edge SPEC §1 draws a compiled fact rather than a line in a build file, and it
   * gives checkModuleBoundaries something real to police.
   */
  val reaches: List<String> = listOf(CoreApiArtifact.NAME)
}
