package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.*
import java.time.Instant
import kotlin.test.*

class NavigationTest {
  private val now = Instant.parse("2026-09-27T12:00:00Z")
  private val start = RuntimeStart(RuntimeStartId("current"), now)
  private val workspace = WorkspaceState(Workspace(WorkspaceId("one"), "one", "/tmp/one", AccessLevel.Read), true)
  private fun entry(id: String, run: RuntimeStartId) = ActivityEntry(
    ActivityEntryId(id), now, run, Origin.ChatGpt, "one", "read_file", "path=$id", null,
    ActivityOutcome.Lost, 0, null,
  )
  private fun home() = Home().observed(RuntimeEvent.Snapshot(
    listOf(workspace), RuntimeStatus(RuntimeState.Connected, now), emptyList(),
    listOf(entry("old-record", RuntimeStartId("old")), entry("current-record", start.id)), start,
  ))
  private fun Home.key(key: String) = press(Key(key)).home
  private fun Home.text() = render(this, Frame(100, 24)).joinToString("\n") { it.plain }

  @Test fun `activity is separate and older records remain stored but cannot be selected`() {
    val home = home()
    assertFalse("path=current-record" in home.text())
    val activity = home.key("a")
    assertTrue("path=current-record" in activity.text())
    assertFalse("old-record" in activity.text())
    assertEquals(2, activity.snapshot!!.activity.size)
    assertEquals(listOf(ActivityEntryId("current-record")), activity.feed.map { it.id })
    assertEquals(ActivityEntryId("current-record"), activity.key("ArrowUp").key("ArrowUp").selectedEntry?.id)
    val restarted = activity.observed(activity.snapshot!!.copy(start = RuntimeStart(RuntimeStartId("next"), now)))
    assertTrue(restarted.feed.isEmpty())
    assertFalse("current-record" in restarted.text())
  }

  @Test fun `workspace and runtime actions are scoped to their screens`() {
    val home = home()
    assertNull(home.press(Key("3")).command)
    assertNull(home.press(Key("3")).home.overlay)
    assertNull(home.press(Key("X")).home.overlay)
    val manage = home.key("m")
    assertNotNull(manage.key("3").overlay)
    assertTrue("/tmp/one" in manage.text())
    val runtime = home.key("i")
    assertNotNull(runtime.key("Enter").key("X").overlay)
    assertEquals(Command.Quit, home.press(Key("q")).command)
    assertNull(runtime.press(Key("Escape")).command)
  }

  @Test fun `setup instructions appear only after opening the Connector detail in Review`() {
    val home = home()
    assertTrue("Connector · Unconfirmed" in home.text())
    assertFalse("Delete the app" in home.key("i").text())
    assertTrue("Delete the app" in home.key("i").key("ArrowDown").key("ArrowDown").key("Enter").text())
  }

  @Test fun `moving selection after scrolling keeps the workspace being managed visible`() {
    val workspaces = (1..30).map { n ->
      WorkspaceState(Workspace(WorkspaceId("w$n"), "workspace-$n", "/tmp/w$n", AccessLevel.Read), false)
    }
    var home = home().observed(home().snapshot!!.copy(workspaces = workspaces))
    repeat(4) { home = home.key("PageDown") }
    home = home.key("ArrowDown")
    val frame = Frame(80, 24)
    val lines = render(home, frame)
    assertTrue(lines.size <= frame.rows)
    assertTrue(lines.any { line -> line.spans.any { it.selected && "workspace-2" in it.text } })
    assertEquals("workspace-2", home.key("m").chipState!!.workspace.name)
    repeat(25) { home = home.key("ArrowDown") }
    assertTrue(render(home, frame).any { line -> line.spans.any { it.selected && "workspace-27" in it.text } })
  }
}
