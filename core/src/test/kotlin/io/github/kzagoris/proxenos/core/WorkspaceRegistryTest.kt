package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class WorkspaceRegistryTest {
  @TempDir
  lateinit var temporary: Path

  @Test
  fun `failed persistence leaves the previously admitted level unchanged`() = runBlocking<Unit> {
    val state = temporary.resolve("registry.properties")
    val registry = WorkspaceRegistry(state)
    val workspace = registry.perform(ManagementAct.Register(temporary.toString(), "api"))
    val saved = Files.move(state, temporary.resolve("saved.properties"))
    Files.createDirectory(state)
    Files.writeString(state.resolve("obstruction"), "prevent replacement")
    assertFailsWith<IOException> {
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    }
    assertEquals(Outcome.Ok(workspace), registry.admit("api", AccessLevel.Read))
    assertIs<Failure.LevelTooLow>(assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Command)).reason)
    assertEquals(Outcome.Ok(workspace), WorkspaceRegistry(saved).admit("api", AccessLevel.Read))
  }

  @Test
  fun `a missing or nondirectory Root cannot be registered or reconfirmed`() = runBlocking<Unit> {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val root = Files.createDirectory(temporary.resolve("project"))
    val file = Files.writeString(temporary.resolve("file"), "not a directory")
    for (invalid in listOf(file, temporary.resolve("missing"))) {
      assertFailsWith<IOException> { registry.perform(ManagementAct.Register(invalid.toString())) }
    }
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    assertFailsWith<IOException> { registry.perform(ManagementAct.Reconfirm(workspace.id, file.toString())) }
    assertEquals(Outcome.Ok(workspace.copy(accessLevel = AccessLevel.Command)), registry.admit("api", AccessLevel.Command))
    Files.delete(root)
    Files.writeString(root, "replacement file")
    assertIs<Failure.WorkspaceBroken>(assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Read)).reason)
  }

  @Test
  fun `corrupt persisted state is rejected instead of silently forgetting registrations`() {
    val state = Files.writeString(temporary.resolve("registry.properties"), "version=1\ncount=1\n")
    assertFailsWith<IllegalArgumentException> { WorkspaceRegistry(state) }
  }

  @Test
  fun `a Root ending in dot is prefilled with the directory name`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val workspace = registry.perform(ManagementAct.Register(root.resolve(".").toString()))
    assertEquals("project", workspace.name)
  }

  @Test
  fun `malformed Git metadata does not prevent discovery`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    Files.writeString(root.resolve(".git"), "gitdir: bad\u0000path")
    val listings = assertIs<Outcome.Ok<List<WorkspaceListing>>>(operations(registry).perform(Operation.ListWorkspaces))
    assertEquals(listOf(WorkspaceListing("api", root.toString(), AccessLevel.Read, false)), listings.value)
  }

  @Test
  fun `Broken is recomputed at startup and never persisted`() = runBlocking<Unit> {
    val state = temporary.resolve("registry.properties")
    val root = Files.createDirectory(temporary.resolve("project"))
    val moved = temporary.resolve("moved")
    val registry = WorkspaceRegistry(state)
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    val persisted = Files.readAllBytes(state)

    Files.move(root, moved)
    val restarted = WorkspaceRegistry(state)
    Files.move(moved, root)
    // Startup must notice the missing Root even though it returned before the first call.
    assertIs<Failure.WorkspaceBroken>(assertIs<Outcome.Failed>(restarted.admit("api", AccessLevel.Read)).reason)
    assertContentEquals(persisted, Files.readAllBytes(state))
    restarted.perform(ManagementAct.Rename(workspace.id, "renamed"))
    assertEquals(Outcome.Ok(emptyList()), operations(restarted).perform(Operation.ListWorkspaces))

    // A new Runtime recomputes Broken from identity; it must not reload the old flag.
    val restartedAgain = WorkspaceRegistry(state)
    assertEquals(
      Outcome.Ok(workspace.copy(name = "renamed", accessLevel = AccessLevel.Command)),
      restartedAgain.admit("renamed", AccessLevel.Command),
    )
  }

  @Test
  fun `reconfirmation can rebind a moved Root without changing Workspace identity`() = runBlocking<Unit> {
    val state = temporary.resolve("registry.properties")
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(state)
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    val moved = Files.move(root, temporary.resolve("moved"))
    assertIs<Failure.WorkspaceBroken>(assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Read)).reason)
    val confirmed = registry.perform(ManagementAct.Reconfirm(workspace.id, moved.toString()))
    assertEquals(workspace.copy(root = moved.toString()), confirmed)
    val restarted = WorkspaceRegistry(state)
    assertEquals(Outcome.Ok(confirmed), restarted.admit("api", AccessLevel.Read))
    assertIs<Failure.LevelTooLow>(assertIs<Outcome.Failed>(restarted.admit("api", AccessLevel.Command)).reason)
  }

  @Test
  fun `replacement before the next call is Broken including after restart`() = runBlocking<Unit> {
    val state = temporary.resolve("registry.properties")
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(state)
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    Files.move(root, temporary.resolve("moved"))
    Files.createDirectory(root)

    for (instance in listOf(registry, WorkspaceRegistry(state))) {
      assertIs<Failure.WorkspaceBroken>(assertIs<Outcome.Failed>(instance.admit("api", AccessLevel.Command)).reason)
      assertEquals(Outcome.Ok(emptyList()), operations(instance).perform(Operation.ListWorkspaces))
      val missing = assertIs<Outcome.Failed>(instance.admit("unknown", AccessLevel.Read))
      assertEquals(Failure.NoSuchWorkspace(emptyList()), missing.reason)
      assertFalse(missing.message.contains("api"))
    }
  }

  @Test
  fun `root and home directories register like any other directory`() = runBlocking<Unit> {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val root = registry.perform(ManagementAct.Register("/"))
    val home = registry.perform(ManagementAct.Register(System.getProperty("user.home"), "home"))
    assertEquals("/", root.root)
    assertEquals("/", root.name)
    assertEquals(Outcome.Ok(root), registry.admit("/", AccessLevel.Read))
    assertEquals(Outcome.Ok(home), registry.admit("home", AccessLevel.Read))
  }

  @Test
  fun `retargeting a symlink Root is Broken but changing contents is not`() = runBlocking<Unit> {
    val first = Files.createDirectory(temporary.resolve("first"))
    val second = Files.createDirectory(temporary.resolve("second"))
    val link = Files.createSymbolicLink(temporary.resolve("link"), first)
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val workspace = registry.perform(ManagementAct.Register(link.toString(), "api"))
    Files.writeString(first.resolve("new-file"), "contents")
    assertEquals(Outcome.Ok(workspace), registry.admit("api", AccessLevel.Read))
    Files.delete(link)
    Files.createSymbolicLink(link, second)
    assertIs<Failure.WorkspaceBroken>(assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Read)).reason)
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
    assertIs<Failure.NoSuchWorkspace>(assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Read)).reason)
  }

  @Test
  fun `discovery recognizes linked worktrees and bare repositories`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("project"))
    git(root, "init", "--quiet")
    git(root, "-c", "user.name=Registry Test", "-c", "user.email=registry@example.invalid", "commit", "--allow-empty", "--no-gpg-sign", "-m", "Initial")
    val worktree = temporary.resolve("worktree")
    git(root, "worktree", "add", "--detach", worktree.toString())
    val bare = Files.createDirectory(temporary.resolve("bare"))
    git(bare, "init", "--bare", "--quiet")
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    registry.perform(ManagementAct.Register(worktree.toString()))
    registry.perform(ManagementAct.Register(bare.toString()))
    val listings = assertIs<Outcome.Ok<List<WorkspaceListing>>>(operations(registry).perform(Operation.ListWorkspaces))
    assertEquals(listOf("worktree", "bare"), listings.value.filter { it.isGitRepository }.map { it.name })
  }

  @Test
  fun `discovery notices a Git repository created after registration`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    registry.perform(ManagementAct.Register(root.toString()))
    git(root, "init", "--quiet")
    val listing = assertIs<Outcome.Ok<List<WorkspaceListing>>>(operations(registry).perform(Operation.ListWorkspaces))
    assertTrue(listing.value.single().isGitRepository)
    Files.move(root.resolve(".git"), temporary.resolve("git-metadata"))
    val changed = assertIs<Outcome.Ok<List<WorkspaceListing>>>(operations(registry).perform(Operation.ListWorkspaces))
    assertFalse(changed.value.single().isGitRepository)
  }

  /** Discovery is an Operation, so it is reached the way every Operation is (SPEC §9). */
  private fun operations(registry: WorkspaceRegistry): WorkspaceOperations =
    WorkspaceOperationsPipeline(registry, activity()).operationsFor(Origin.ChatGpt)

  private fun activity() = Activity(temporary.resolve("activity"))

  private fun git(root: Path, vararg arguments: String) {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, process.waitFor(), output)
  }

  @Test
  fun `registrations and every Access Level survive restart exactly as set`() = runBlocking {
    val state = temporary.resolve("state/registry.properties")
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(state)
    val registered = AccessLevel.entries.map { level ->
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "at-$level"))
      registry.perform(ManagementAct.SetLevel(workspace.id, level))
    }
    registry.perform(ManagementAct.Rename(registered.last().id, "renamed-command"))

    val restarted = WorkspaceRegistry(state)
    for (workspace in registered) {
      val name = if (workspace.accessLevel == AccessLevel.Command) "renamed-command" else workspace.name
      if (workspace.accessLevel == AccessLevel.None) {
        assertIs<Failure.NoSuchWorkspace>(assertIs<Outcome.Failed>(restarted.admit(name, AccessLevel.Read)).reason)
        assertFailsWith<IllegalArgumentException> {
          restarted.perform(ManagementAct.Register(root.toString(), name))
        }
        val raised = restarted.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
        assertEquals(workspace.copy(accessLevel = AccessLevel.Read), raised)
      } else {
        assertEquals(Outcome.Ok(workspace.copy(name = name)), restarted.admit(name, workspace.accessLevel))
      }
    }
  }

  @Test
  fun `a moved Root becomes Broken and a replacement cannot inherit access`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    Files.move(root, temporary.resolve("moved"))

    assertEquals(Outcome.Ok(emptyList()), operations(registry).perform(Operation.ListWorkspaces))
    for (required in AccessLevel.entries) {
      val denied = assertIs<Outcome.Failed>(registry.admit("api", required))
      assertEquals(Failure.WorkspaceBroken("api"), denied.reason)
      assertContains(denied.message, "api")
    }
    Files.createDirectory(root)
    assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Read))
    val confirmed = registry.perform(ManagementAct.Reconfirm(workspace.id))
    assertEquals(workspace, confirmed)
    assertEquals(Outcome.Ok(confirmed), registry.admit("api", AccessLevel.Read))
    assertIs<Failure.LevelTooLow>(assertIs<Outcome.Failed>(registry.admit("api", AccessLevel.Write)).reason)
    Unit
  }

  @Test
  fun `registration and rename refuse duplicate names even when withheld`() = runBlocking {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val hidden = registry.perform(ManagementAct.Register(temporary.toString(), "taken"))
    registry.perform(ManagementAct.SetLevel(hidden.id, AccessLevel.None))
    assertFailsWith<IllegalArgumentException> {
      registry.perform(ManagementAct.Register(temporary.toString(), "taken"))
    }
    val other = registry.perform(ManagementAct.Register(temporary.toString(), "other"))
    assertNotEquals(hidden.id, other.id)
    assertFailsWith<IllegalArgumentException> {
      registry.perform(ManagementAct.Rename(other.id, "taken"))
    }
    assertEquals(Outcome.Ok(other), registry.admit("other", AccessLevel.Read))
    assertEquals(hidden.copy(accessLevel = AccessLevel.None), registry.perform(ManagementAct.Rename(hidden.id, "taken")))
    assertEquals("Taken", registry.perform(ManagementAct.Register(temporary.toString(), "Taken")).name)
  }

  @Test
  fun `overlapping Roots apply only the named Workspace's Access Level`() = runBlocking {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val nested = Files.createDirectory(temporary.resolve("api"))
    val parent = registry.perform(ManagementAct.Register(temporary.toString(), "parent"))
    val child = registry.perform(ManagementAct.Register(nested.toString(), "child"))
    val commandChild = registry.perform(ManagementAct.SetLevel(child.id, AccessLevel.Command))

    for ((level, allowed) in listOf(
      AccessLevel.Read to setOf(AccessLevel.Read),
      AccessLevel.Write to setOf(AccessLevel.Read, AccessLevel.Write),
      AccessLevel.Command to setOf(AccessLevel.Read, AccessLevel.Write, AccessLevel.Command),
    )) {
      val updated = registry.perform(ManagementAct.SetLevel(parent.id, level))
      for (required in listOf(AccessLevel.Read, AccessLevel.Write, AccessLevel.Command)) {
        val result = registry.admit("parent", required)
        if (required in allowed) {
          assertEquals(Outcome.Ok(updated), result)
        } else {
          val denied = assertIs<Outcome.Failed>(result)
          assertEquals(Failure.LevelTooLow("parent", level, required), denied.reason)
          assertContains(denied.message, level.name)
          assertContains(denied.message, required.name)
        }
      }
      assertEquals(Outcome.Ok(commandChild), registry.admit("child", AccessLevel.Command))
    }
    registry.perform(ManagementAct.SetLevel(parent.id, AccessLevel.None))
    assertEquals(Outcome.Ok(commandChild), registry.admit("child", AccessLevel.Command))
  }

  @Test
  fun `None hides a Workspace from discovery and every admission error`() = runBlocking {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val hidden = registry.perform(ManagementAct.Register(temporary.toString(), "secret"))
    registry.perform(ManagementAct.Register(temporary.toString(), "visible"))
    registry.perform(ManagementAct.SetLevel(hidden.id, AccessLevel.None))

    val listings = assertIs<Outcome.Ok<List<WorkspaceListing>>>(operations(registry).perform(Operation.ListWorkspaces))
    assertEquals(listOf("visible"), listings.value.map { it.name })
    for (required in AccessLevel.entries) {
      val denied = assertIs<Outcome.Failed>(registry.admit("secret", required))
      assertEquals(registry.admit("unregistered", required), denied)
      assertEquals(Failure.NoSuchWorkspace(listOf("visible")), denied.reason)
      assertContains(denied.message, "visible")
      assertFalse(denied.message.contains("secret"))
    }
  }

  @Test
  fun `renaming preserves identity and only the exact new name resolves`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    val renamed = registry.perform(ManagementAct.Rename(workspace.id, "API"))

    assertEquals(workspace.copy(name = "API"), renamed)
    assertEquals(Outcome.Ok(renamed), registry.admit("API", AccessLevel.Read))
    for (name in listOf("api", "Api", "AP", " API", "API ")) {
      val denied = assertIs<Outcome.Failed>(registry.admit(name, AccessLevel.Read))
      assertIs<Failure.NoSuchWorkspace>(denied.reason)
      assertContains(denied.message, "API")
    }
  }

  @Test
  fun `registering a directory generates an id and discovers it at Read`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = activity()
    val management: WorkspaceManagement = RuntimeManagement(
      registry, activity, unstartedTunnel(temporary), WorkspaceOperationsPipeline(registry, activity),
      ConnectorAcknowledgement(temporary.resolve("connector")), RuntimeFeed(),
    )
    val workspace = management.perform(ManagementAct.Register(root.toString()))

    UUID.fromString(workspace.id.value)
    assertEquals("project", workspace.name)
    val operations: WorkspaceOperations = operations(registry)
    assertEquals(
      Outcome.Ok(listOf(WorkspaceListing("project", root.toString(), AccessLevel.Read, false))),
      operations.perform(Operation.ListWorkspaces),
    )
  }
}
