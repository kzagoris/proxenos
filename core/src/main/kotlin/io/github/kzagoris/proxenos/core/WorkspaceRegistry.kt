package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Owns registrations; filesystem and persistence details never cross into core-api. It answers
 * the acts that change a registration and no others — [RuntimeManagement] is the seam a
 * frontend holds, and it is what routes the acts that are nobody's registration.
 */
class WorkspaceRegistry(
  private val registryFile: Path,
  /** Where a frontend learns of each registration change, published under this registry's lock so they arrive in order. */
  private val feed: RuntimeFeed = RuntimeFeed(),
) {
  private val lock = Any()
  private var registrations = load()
  private val broken = mutableSetOf<WorkspaceId>()

  init {
    synchronized(lock) { registrations.forEach { publish(it) } }
  }

  /** The shared name/level check for scoped Operations arriving in the admission pipeline. */
  fun admit(name: String, required: AccessLevel): Outcome<Workspace> = synchronized(lock) {
    val registration = registrations.find { it.workspace.name == name }
    val workspace = registration?.workspace
    when {
      workspace == null || workspace.accessLevel == AccessLevel.None -> {
        val exposed = exposedNames()
        Outcome.Failed(
          Failure.NoSuchWorkspace(exposed),
          "No such Workspace. Exposed Workspaces: ${exposed.joinToString()}",
        )
      }
      isBroken(registration) -> Outcome.Failed(
        Failure.WorkspaceBroken(name), "Workspace '$name' is Broken; re-confirm its Root.",
      )
      !workspace.accessLevel.includes(required) -> Outcome.Failed(
        Failure.LevelTooLow(name, workspace.accessLevel, required),
        "Workspace '$name' is at ${workspace.accessLevel}; this Operation requires $required.",
      )
      else -> Outcome.Ok(workspace)
    }
  }

  /** What discovery shows: Workspaces at None and Broken ones are absent entirely. */
  fun listings(): List<WorkspaceListing> = synchronized(lock) {
    exposed().map {
      val workspace = it.workspace
      WorkspaceListing(workspace.name, workspace.root, workspace.accessLevel, isGitRepository(Path.of(workspace.root)))
    }
  }

  /** The names an admission error may disclose, which is exactly the exposed ones. */
  fun exposedNames(): List<String> = synchronized(lock) { exposed().map { it.workspace.name } }

  @Suppress("UNCHECKED_CAST") // Each sealed act fixes its result type.
  suspend fun <R> perform(act: ManagementAct.OnRegistry<R>): R = synchronized(lock) {
    if (act is ManagementAct.Forget) return@synchronized forget(act.id) as R
    val registration = when (act) {
      is ManagementAct.SetLevel -> {
        val previous = find(act.id)
        previous.copy(workspace = previous.workspace.copy(accessLevel = act.level))
      }
      is ManagementAct.Rename -> {
        requireUniqueName(act.name, act.id)
        val previous = find(act.id)
        previous.copy(workspace = previous.workspace.copy(name = act.name))
      }
      is ManagementAct.Reconfirm -> {
        val previous = find(act.id)
        val root = Path.of(act.root ?: previous.workspace.root).toAbsolutePath()
        Registration(
          previous.workspace.copy(root = root.toString(), accessLevel = AccessLevel.Read),
          identify(root),
        )
      }
      is ManagementAct.Register -> {
        val root = Path.of(act.root).toAbsolutePath()
        val name = act.name ?: root.fileName?.toString()?.takeUnless { it == "." || it == ".." }
          ?: root.toRealPath().fileName?.toString() ?: root.toString()
        requireUniqueName(name)
        Registration(
          Workspace(WorkspaceId(UUID.randomUUID().toString()), name, root.toString(), AccessLevel.Read),
          identify(root),
        )
      }
      is ManagementAct.Forget -> error("Forget is answered above")
    }
    val id = registration.workspace.id
    val updated = if (act is ManagementAct.Register) registrations + registration
    else registrations.map { if (it.workspace.id == id) registration else it }
    persist(updated)
    registrations = updated
    if (act is ManagementAct.Reconfirm) broken.remove(id)
    publish(registration)
    registration.workspace as R
  }

  /** Only the registration goes. What was done in it stays in Activity, which is not the registry's to edit. */
  private fun forget(id: WorkspaceId) {
    find(id)
    val updated = registrations.filter { it.workspace.id != id }
    persist(updated)
    registrations = updated
    broken.remove(id)
    feed.publish(RuntimeEvent.Change.WorkspaceForgotten(id))
  }

  /** Under [lock], so two changes to one Workspace reach every frontend in the order they were made. */
  private fun publish(registration: Registration) =
    feed.publish(RuntimeEvent.Change.WorkspaceChanged(WorkspaceState(registration.workspace, isBroken(registration))))

  private fun find(id: WorkspaceId): Registration =
    requireNotNull(registrations.find { it.workspace.id == id }) { "No registered Workspace with id ${id.value}" }

  private fun requireUniqueName(name: String, except: WorkspaceId? = null) {
    require(registrations.none { it.workspace.id != except && it.workspace.name == name }) {
      "Workspace name already registered: $name"
    }
  }

  private fun exposed(): List<Registration> = registrations.filter {
    it.workspace.accessLevel != AccessLevel.None && !isBroken(it)
  }

  private fun isBroken(registration: Registration): Boolean {
    val workspace = registration.workspace
    if (workspace.id in broken) return true
    val current = try {
      identify(Path.of(workspace.root))
    } catch (_: IOException) {
      null
    }
    // Broken is found by looking, so a frontend learns of it the first time anything looks.
    if (current != registration.identity && broken.add(workspace.id)) publish(registration)
    return workspace.id in broken
  }

  private fun identify(root: Path): DirectoryIdentity {
    val attributes = Files.readAttributes(root, BasicFileAttributes::class.java)
    if (!attributes.isDirectory) throw IOException("Root must resolve to a directory: $root")
    val inode = Files.getAttribute(root, "unix:ino") as Long
    // The inode distinguishes replacement; birth time also protects against inode reuse. Not the
    // device: a btrfs subvolume's number, like a device-mapper volume's, is handed out at mount
    // time and changes across a reboot, which would make every Root read as replaced.
    return DirectoryIdentity("ino=$inode", attributes.creationTime().toString())
  }

  /**
   * A recorded key as [identify] writes it now. Registries written before the device was dropped
   * hold `(dev=…,ino=…)`, and it is the inode in it that still names the directory.
   */
  private fun inodeKey(recorded: String): String =
    Regex("""\bino=(\d+)""").find(recorded)?.let { "ino=${it.groupValues[1]}" } ?: recorded

  /** An attribute of the Root, discovered from Git's layout rather than from `git`. */
  private fun isGitRepository(root: Path): Boolean = gitRepositoryAt(root)


  private fun load(): List<Registration> {
    val state = Properties()
    try {
      Files.newInputStream(registryFile).use { state.load(it) }
    } catch (_: NoSuchFileException) {
      return emptyList()
    }
    fun required(key: String): String = requireNotNull(state.getProperty(key)) { "Registry is missing $key" }
    require(required("version") == "1") { "Unsupported registry version" }
    val count = required("count").toInt()
    require(count >= 0) { "Invalid registry count" }
    val loaded = (0 until count).map { index ->
      val prefix = "workspace.$index"
      Registration(
        Workspace(
          WorkspaceId(UUID.fromString(required("$prefix.id")).toString()),
          required("$prefix.name"),
          required("$prefix.root"),
          AccessLevel.valueOf(required("$prefix.level")),
        ),
        DirectoryIdentity(inodeKey(required("$prefix.identity.key")), required("$prefix.identity.created")),
      )
    }
    require(loaded.map { it.workspace.id }.distinct().size == count) { "Duplicate Workspace ids in registry" }
    require(loaded.map { it.workspace.name }.distinct().size == count) { "Duplicate Workspace names in registry" }
    require(loaded.all { Path.of(it.workspace.root).isAbsolute }) { "Registry Roots must be absolute" }
    return loaded
  }

  private fun persist(updated: List<Registration>) {
    val state = Properties()
    state.setProperty("version", "1")
    state.setProperty("count", updated.size.toString())
    updated.forEachIndexed { index, registration ->
      val prefix = "workspace.$index"
      val workspace = registration.workspace
      state.setProperty("$prefix.id", workspace.id.value)
      state.setProperty("$prefix.name", workspace.name)
      state.setProperty("$prefix.root", workspace.root)
      state.setProperty("$prefix.level", workspace.accessLevel.name)
      state.setProperty("$prefix.identity.key", registration.identity.key)
      state.setProperty("$prefix.identity.created", registration.identity.created)
    }
    val destination = registryFile.toAbsolutePath()
    Files.createDirectories(destination.parent)
    val temporary = Files.createTempFile(destination.parent, ".registry-", ".tmp")
    try {
      Files.newOutputStream(temporary).use { state.store(it, "Workspace registrations v1") }
      Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private data class DirectoryIdentity(val key: String, val created: String)
  private data class Registration(val workspace: Workspace, val identity: DirectoryIdentity)
}
