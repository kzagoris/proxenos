package io.github.kzagoris.proxenos.coreapi

import kotlinx.serialization.Serializable

@Serializable
enum class AccessLevel {
  None, Read, Write, Command;

  fun includes(required: AccessLevel): Boolean = this >= required
}

@Serializable
data class WorkspaceId(val value: String)

@Serializable
data class Workspace(
  val id: WorkspaceId,
  val name: String,
  val root: String,
  val accessLevel: AccessLevel,
)

@Serializable
data class WorkspaceListing(
  val name: String,
  val root: String,
  val accessLevel: AccessLevel,
  val isGitRepository: Boolean,
)
