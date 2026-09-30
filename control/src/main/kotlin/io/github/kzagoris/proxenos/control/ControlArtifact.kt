package io.github.kzagoris.proxenos.control

import io.github.kzagoris.proxenos.coreapi.CoreApiArtifact

/**
 * The artifact's name. What it holds is [ManagementClient], which a frontend is given, and
 * [ControlServer], which the Runtime serves it with.
 */
object ControlArtifact {
  const val NAME: String = "control"

  /**
   * Nothing reads this, and that is not an oversight: referencing the artifact above is what
   * makes the edge the design draws a compiled fact rather than a line in a build file, and it
   * gives checkModuleBoundaries something real to police.
   */
  val reaches: List<String> = listOf(CoreApiArtifact.NAME)
}
