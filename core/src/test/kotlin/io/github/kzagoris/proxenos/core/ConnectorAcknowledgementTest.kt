package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * All this can prove: the banner behaves. Its subject is the connector
 * in ChatGPT, which nothing here can see, so every "confirmed" below is the user's word read back
 * and never a claim that a connector exists.
 *
 * A restart is a new Runtime over the same state directory, which is all a restart leaves behind.
 */
class ConnectorAcknowledgementTest {
  @TempDir
  lateinit var temporary: Path

  /** Hashing the shipped catalog, as the Runtime does, unless a test ships a different one. */
  private inner class Boot(catalog: List<OperationSpec> = OperationCatalog.ENTRIES) {
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"), feed)
    val activity = Activity(temporary.resolve("activity"), feed = feed)
    val pipeline = WorkspaceOperationsPipeline(registry, activity)
    val management: WorkspaceManagement = RuntimeManagement(
      registry, activity, unstartedTunnel(temporary), pipeline,
      ConnectorAcknowledgement(temporary.resolve("connector"), feed, catalogFingerprint(catalog)), feed,
    )

    fun unconfirmed(): Boolean = runBlocking { (management.observe().first() as RuntimeEvent.Snapshot).connectorUnconfirmed }
  }

  @Test
  fun `first run and re-creation are one path - absent or different reads Unconfirmed until the user says otherwise`() = runBlocking<Unit> {
    val fresh = Boot()
    assertTrue(fresh.unconfirmed(), "a fresh install has no stored fingerprint")

    fresh.management.perform(ManagementAct.AcknowledgeConnector)
    assertFalse(fresh.unconfirmed(), "the user's word alone clears it")

    assertFalse(Boot().unconfirmed(), "a restart changes no catalog entry, so it raises nothing")

    val shipped = OperationCatalog.ENTRIES
    val changed = listOf(shipped.first().let { it.copy(description = it.description + " Now different.") }) + shipped.drop(1)
    val upgraded = Boot(changed)
    assertTrue(upgraded.unconfirmed(), "a catalog that differs is a snapshot ChatGPT does not have")

    upgraded.management.perform(ManagementAct.AcknowledgeConnector)
    assertFalse(Boot(changed).unconfirmed())
  }

  @Test
  fun `an acknowledgement reaches a frontend already attached`() = runBlocking<Unit> {
    val runtime = Boot()
    var state: RuntimeEvent.Snapshot? = null

    runtime.management.observe().first { event ->
      state = when (event) {
        is RuntimeEvent.Snapshot -> event.also {
          assertTrue(it.connectorUnconfirmed)
          runtime.management.perform(ManagementAct.AcknowledgeConnector)
        }
        is RuntimeEvent.Change -> assertNotNull(state).after(event)
      }
      !state!!.connectorUnconfirmed
    }
  }

  /** The catalog is flat and static: nothing a frontend does to a registration is in it. */
  @Test
  fun `registrations and Access Levels, Command included, never re-raise it`() = runBlocking<Unit> {
    val first = Boot()
    first.management.perform(ManagementAct.AcknowledgeConnector)
    val root = Files.createDirectory(temporary.resolve("project"))

    val workspace = first.management.perform(ManagementAct.Register(root.toString(), "api"))
    first.management.perform(ManagementAct.Rename(workspace.id, "service"))
    first.management.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    first.management.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
    assertFalse(first.unconfirmed())

    val restarted = Boot()
    restarted.management.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    restarted.management.perform(ManagementAct.Forget(workspace.id))
    assertFalse(Boot().unconfirmed())
  }
}
