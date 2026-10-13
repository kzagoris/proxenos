package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.*
import java.time.Instant
import kotlin.test.*

class SetupProgressTest {
  private val at = Instant.parse("2026-10-13T12:00:00Z")
  private val start = RuntimeStartId("start")
  private val workspace = WorkspaceState(Workspace(WorkspaceId("notes"), "Notes", "/tmp/notes", AccessLevel.Read), false)
  private val progress = SetupProgress(SetupStep.TryIt, workspace = "notes", waitingSince = at.toEpochMilli(), waitingRoot = "/tmp/notes", waitingName = "Notes")
  private fun entry(origin: Origin = Origin.ChatGpt, time: Instant = at, name: String = "Notes",
    arguments: String = "path=.", outcome: ActivityOutcome = ActivityOutcome.Ok("files"), runtime: RuntimeStartId = start) =
    ActivityEntry(ActivityEntryId("entry"), time, runtime, origin, name, "list_directory", arguments, null, outcome, 0, null)
  private fun snapshot(entry: ActivityEntry) = RuntimeEvent.Snapshot(listOf(workspace), RuntimeStatus(RuntimeState.Connected, at),
    emptyList(), activity = listOf(entry), start = RuntimeStart(start, at), connectorUnconfirmed = false)

  @Test
  fun `only a successful new ChatGPT Root listing of the selected Workspace verifies setup`() {
    assertNotNull(progress.evidence(snapshot(entry())))
    listOf(entry(origin = Origin.Frontend), entry(time = at.minusMillis(1)), entry(name = "Other"),
      entry(arguments = "path=subdirectory"), entry(outcome = ActivityOutcome.Failed("refused")),
      entry(outcome = ActivityOutcome.Undelivered(ActivityOutcome.Ok("files"))), entry(runtime = RuntimeStartId("older")))
      .forEach { assertNull(progress.evidence(snapshot(it)), it.toString()) }
    assertNull(progress.evidence(snapshot(entry()).copy(workspaces = listOf(workspace.copy(workspace = workspace.workspace.copy(root = "/tmp/other"))))))
    assertNull(progress.evidence(snapshot(entry(name = "Renamed")).copy(workspaces = listOf(workspace.copy(workspace = workspace.workspace.copy(name = "Renamed"))))))
    assertNull(progress.evidence(null))
    assertNull(progress.evidence(snapshot(entry()).copy(workspaces = listOf(workspace.copy(broken = true)))))
  }

  @Test
  fun `onboarding and the selected Workspace survive an attachment loss and restart`() {
    val initial = GuiState(setup = progress, setupVisible = true)
    val attached = initial.observed(io.github.kzagoris.proxenos.frontend.Attachment.Attached(snapshot(entry())))
    assertEquals(progress, attached.setup)
    assertTrue(attached.setupVisible)
    val absent = attached.observed(io.github.kzagoris.proxenos.frontend.Attachment.Absent(io.github.kzagoris.proxenos.frontend.Reason.NotAnswering))
    assertEquals(progress, absent.starting().setup)
  }
}
