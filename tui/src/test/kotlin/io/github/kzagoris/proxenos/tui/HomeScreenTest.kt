package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * The home screen read as a user reads it: through [render], after the keys [Home.press] was
 * given. Nothing here draws to a terminal; `--dump` prints exactly these lines.
 */
class HomeScreenTest {
  @TempDir
  lateinit var temporary: Path

  private val now = Instant.parse("2026-09-23T12:00:00Z")
  private val frame = Frame(columns = 140, rows = 60, zone = ZoneOffset.UTC, now = now)
  private val thisStart = RuntimeStart(RuntimeStartId("this"), now.minusSeconds(3600))
  private val earlier = RuntimeStartId("earlier")

  private fun workspace(name: String, level: AccessLevel = AccessLevel.Read, root: String = "/home/u/$name", broken: Boolean = false) =
    WorkspaceState(Workspace(WorkspaceId(name), name, root, level), broken)

  private var minute = 0L

  private fun entry(
    tool: String,
    outcome: ActivityOutcome,
    start: RuntimeStartId = thisStart.id,
    arguments: String = "",
    acknowledged: Boolean = false,
  ) = ActivityEntry(
    id = ActivityEntryId("$tool-${minute}"),
    at = now.minusSeconds(3000 - 60 * minute++),
    runtimeStart = start,
    origin = Origin.ChatGpt,
    workspace = "notes",
    tool = tool,
    arguments = arguments,
    elapsed = if (outcome == ActivityOutcome.Lost || outcome == ActivityOutcome.InFlight) null else 40.milliseconds,
    outcome = outcome,
    deliveries = 0,
    acknowledgedAt = if (acknowledged) now else null,
  )

  private fun attached(
    workspaces: List<WorkspaceState> = listOf(workspace("notes")),
    activity: List<ActivityEntry> = emptyList(),
    state: RuntimeState = RuntimeState.Connected,
    running: List<RunningOperation> = emptyList(),
    // A machine whose connector was confirmed, unless a test is about the setup detail.
    connectorUnconfirmed: Boolean = false,
    connectingWords: ConnectingWords? = null,
  ) = Home(page = Page.Activity).observed(
    RuntimeEvent.Snapshot(
      workspaces, RuntimeStatus(state, now.minusSeconds(60)), running, activity, thisStart, connectorUnconfirmed, connectingWords,
    ),
  )

  private fun Home.keys(vararg names: String): Home = names.fold(this) { home, name -> home.press(Key(name)).home }

  private fun Home.screen(): List<String> = render(this, frame).map { it.plain }

  /** The whole screen as one paragraph, so a sentence the renderer wrapped can be found whole. */
  private fun Home.prose(): String = screen().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ")

  private fun Home.selectedLine(): Int = screen().indexOfFirst { it.startsWith(" >") || it.startsWith("!>") }

  @Test
  fun `the cursor walks the feed in the order it is drawn - up goes to the row above`() {
    val feed = listOf(entry("read_file", ok(), arguments = "path=first"), entry("read_file", ok(), arguments = "path=second"), entry("read_file", ok(), arguments = "path=third"))
    val home = attached(activity = feed)
    val drawn = home.screen()
    assertTrue(drawn.indexOfFirst { "path=first" in it } < drawn.indexOfFirst { "path=third" in it }, "oldest at the top")

    val onNewest = home.keys("ArrowUp")
    assertTrue("path=third" in onNewest.screen()[onNewest.selectedLine()], "the first press lands on the newest, at the bottom")

    val up = onNewest.keys("ArrowUp")
    assertTrue("path=second" in up.screen()[up.selectedLine()])
    assertEquals(onNewest.selectedLine() - 1, up.selectedLine(), "up moves the cursor to the line drawn above it")

    val down = up.keys("ArrowDown")
    assertEquals(up.selectedLine() + 1, down.selectedLine(), "down moves it to the line drawn below it")
    assertEquals(onNewest.selectedLine(), down.keys("ArrowDown").selectedLine(), "and it stops at the bottom")
  }

  @Test
  fun `unresolved entries are flagged in the gutter and counted, and a refused call is neither`() {
    val home = attached(
      activity = listOf(
        entry("read_file", ActivityOutcome.Failed("No such Workspace."), arguments = "refused-call"),
        entry("run_command", ActivityOutcome.Uncertain("stopped"), arguments = "uncertain-one"),
        entry("write_file", ActivityOutcome.Lost, start = earlier, arguments = "lost-one"),
        entry("edit_file", ActivityOutcome.Undelivered(ok()), arguments = "undelivered-one"),
        entry("run_command", ActivityOutcome.Unclaimed(ok()), arguments = "unclaimed-one"),
        entry("run_command", ActivityOutcome.Uncertain("stopped"), arguments = "seen-one", acknowledged = true),
      ).sortedBy { it.runtimeStart != earlier },
    )
    val screen = home.screen()
    fun gutter(marker: String) = screen.single { marker in it }.first()
    for (unresolved in listOf("uncertain-one", "undelivered-one", "unclaimed-one")) assertEquals('!', gutter(unresolved), unresolved)
    assertEquals(' ', gutter("refused-call"), "a refused call is recorded but not flagged")
    assertEquals(' ', gutter("seen-one"), "an Acknowledged one is settled")
    assertTrue(screen.any { "3 unresolved" in it }, screen.joinToString("\n"))
  }

  @Test
  fun `earlier activity is omitted even when it needs attention`() {
    val old = entry("write_file", ActivityOutcome.Lost, start = earlier, arguments = "path=old")
    val current = entry("read_file", ok(), arguments = "path=current")
    val home = attached(activity = listOf(old, current))
    assertFalse(home.screen().any { "path=old" in it })
    assertTrue(home.screen().any { "path=current" in it })
    assertEquals(listOf(old, current), home.snapshot!!.activity)
    assertTrue(home.screen().any { "nothing unresolved" in it })
  }

  @Test
  fun `an empty current run does not fall back to older activity`() {
    val home = attached(activity = listOf(entry("read_file", ok(), start = earlier)))
    assertTrue(home.feed.isEmpty())
    assertTrue(home.screen().any { "nothing recorded yet" in it })
  }

  @Test
  fun `workspaces are selectable vertical rows with directories and actionable problems`() {
    val home = attached(workspaces = listOf(workspace("notes"), workspace("site", AccessLevel.Write), workspace("old", broken = true))).copy(page = Page.Workspaces)
    val screen = home.screen()
    assertTrue(screen.any { "notes · Read" in it })
    assertTrue(screen.any { "/home/u/site" in it })
    assertTrue(screen.any { "Workspace unavailable" in it })
    val selected = home.keys("ArrowDown", "m")
    assertEquals("site", selected.chipState!!.workspace.name)
    assertEquals(ManagementAct.SetLevel(WorkspaceId("site"), AccessLevel.Read), (selected.press(Key("1")).command as Command.Perform).act)
  }

  @Test
  fun `raising to Command says what it authorises and waits for y`() {
    val home = attached(workspaces = listOf(workspace("scripts", AccessLevel.Write)))
    val asked = home.keys("Escape", "m").press(Key("3"))
    assertNull(asked.command, "nothing is raised before the wording is read")
    val prose = asked.home.prose()
    assertTrue("full authority of your Linux account" in prose, prose)
    assertTrue("not bounded by the Root /home/u/scripts" in prose, prose)
    assertTrue("~/.ssh" in prose, prose)
    assertTrue("There is no per-call prompt" in prose && "while nobody is watching" in prose, prose)

    assertEquals(Command.Perform(ManagementAct.SetLevel(WorkspaceId("scripts"), AccessLevel.Command), "'scripts' is now at Command."), asked.home.press(Key("y")).command)
    val declined = asked.home.press(Key("n"))
    assertNull(declined.command, "any other key cancels")
    assertNull(declined.home.overlay)
  }

  @Test
  fun `lowering a level is done at once`() {
    val step = attached(workspaces = listOf(workspace("scripts", AccessLevel.Command))).keys("Escape", "m").press(Key("1"))
    assertEquals(ManagementAct.SetLevel(WorkspaceId("scripts"), AccessLevel.Read), (step.command as Command.Perform).act)
  }

  @Test
  fun `registering an overlapping Root warns, names the overlap, and says there is no union or intersection`() {
    val parent = Files.createDirectories(temporary.resolve("projects"))
    val inside = Files.createDirectories(parent.resolve("site"))
    val home = attached(workspaces = listOf(workspace("projects", AccessLevel.Write, root = parent.toString())))

    val typed = home.copy(page = Page.Workspaces).keys("n", *inside.toString().map { it.toString() }.toTypedArray(), "Enter")
    val warned = typed.press(Key("Enter"))
    assertNull(warned.command, "nothing is registered before the warning is read")
    val prose = warned.home.prose()
    assertTrue("it is inside 'projects' ($parent), at Write" in prose, prose)
    assertTrue("no union and no intersection" in prose, prose)
    assertEquals(ManagementAct.Register(inside.toString(), null), (warned.home.press(Key("y")).command as Command.Perform).act)
  }

  @Test
  fun `registering a Root that overlaps nothing asks nothing more`() {
    val elsewhere = Files.createDirectories(temporary.resolve("elsewhere"))
    val home = attached(workspaces = listOf(workspace("projects", root = temporary.resolve("projects").toString())))
    val step = home.copy(page = Page.Workspaces).keys("n", *elsewhere.toString().map { it.toString() }.toTypedArray(), "Enter", "w", "e", "b").press(Key("Enter"))
    assertEquals(ManagementAct.Register(elsewhere.toString(), "web"), (step.command as Command.Perform).act)
  }

  @Test
  fun `re-confirming a Broken Workspace says it was at Write, lands at Read, and why`() {
    val home = attached(workspaces = listOf(workspace("site", AccessLevel.Write, broken = true)))
    val asked = home.keys("Escape", "m").press(Key("R"))
    assertNull(asked.command)
    val prose = asked.home.prose()
    assertTrue("It was at Write. It lands at Read, not Write" in prose, prose)
    assertTrue("the thing on disk changed identity" in prose, prose)
    assertEquals(ManagementAct.Reconfirm(WorkspaceId("site")), (asked.home.press(Key("y")).command as Command.Perform).act)
  }

  @Test
  fun `the stage details carry the no-autostart statement and the Connected caveat verbatim`() {
    val runtime = attached().keys("i", "Enter").prose()
    assertTrue("There is no autostart, and that is deliberate" in runtime, runtime)
    assertTrue("After a reboot every call from ChatGPT fails until the Runtime is started once" in runtime, runtime)
    assertTrue("expected rather than a fault to hunt" in runtime, runtime)

    val tunnel = attached().keys("i", "ArrowDown", "r").prose()
    assertTrue(Wording.CONNECTED_CAVEAT in tunnel, tunnel)
    assertTrue(
      "Connected means the tunnel between this machine and OpenAI is up. It does not mean ChatGPT still has a " +
        "connector pointed at it: deleting the connector in ChatGPT leaves this reading unchanged, and its catalog " +
        "is a snapshot that never refreshes." in tunnel,
      "the caveat's sentence, word for word",
    )
    // It stays in every state: the caveat explains the signal, not one reading of it.
    assertTrue(Wording.CONNECTED_CAVEAT in attached(state = RuntimeState.Failed(null)).keys("i", "ArrowDown", "r").prose())
    assertTrue(Wording.CONNECTED_CAVEAT in Home().detached(NOT_RUNNING).keys("i", "ArrowDown", "r").prose())
  }

  @Test
  fun `Failed quotes the tunnel in Review and does not read like Disconnected`() {
    val complaint = TunnelComplaint(401, "invalid_api_key", "Check the runtime key.", "Unauthorized")
    val failed = attached(state = RuntimeState.Failed(complaint))
    assertTrue(failed.screen().any { "Tunnel · Failed" in it })
    assertFalse("invalid_api_key" in failed.prose())
    val detail = failed.keys("i", "ArrowDown", "Enter").prose()
    assertTrue("status_code 401" in detail && "invalid_api_key" in detail && "Check the runtime key." in detail)
    assertTrue(attached(state = RuntimeState.Disconnected).screen().any { "Tunnel · Disconnected" in it })
  }

  @Test
  fun `a Runtime that is not running is started with S, and one that is running is not started again`() {
    val absent = Home().detached(NOT_RUNNING)
    assertEquals(Command.StartRuntime, absent.press(Key("S")).command)
    assertNull(attached().press(Key("S")).command)
    // Review does not take the shortcut away: its Runtime row points at [S] in the reason line.
    assertEquals(Command.StartRuntime, absent.keys("i").press(Key("S")).command)
  }

  @Test
  fun `the Review list is the three stages in the order they are fixed, and can't-tell is never a fault`() {
    val detached = Home().detached("refused to start: cannot find the Runtime to start. · [S] try again")
    val rows = render(detached.keys("i"), frame).filter { line -> Stage.entries.any { "${it.name} · " in line.plain } }
    assertEquals(
      listOf("> Runtime · Not running", "Tunnel · Can't tell", "Connector · Can't tell"),
      rows.map { it.plain.trim() },
    )
    // The stage below a Runtime that is not running has nothing to measure: dim, never red.
    for (row in rows.drop(1)) assertEquals(Tone.Dim, row.spans.single { "Can't tell" in it.text }.tone, row.plain)
    val tunnel = detached.keys("i", "ArrowDown", "Enter").prose()
    assertTrue("nothing to measure until the Runtime is running" in tunnel, tunnel)

    // The reason [S] failed is under the Runtime row, in the words that came back.
    assertTrue("cannot find the Runtime to start" in detached.keys("i").prose())
  }

  @Test
  fun `each stage detail owns its action, and the Review list owns none`() {
    val attachedHome = attached()
    // Navigation only on the list: X there is not a Stop.
    assertNull(attachedHome.keys("i").press(Key("X")).command)
    assertNull(attachedHome.keys("i").press(Key("X")).home.overlay)

    // Runtime: [X] Stop, behind its confirmation.
    assertNull(attachedHome.keys("i", "Enter").press(Key("X")).command)
    assertEquals(ManagementAct.Stop, (attachedHome.keys("i", "Enter").press(Key("X")).home.press(Key("y")).command as Command.Perform).act)

    // Tunnel: [d] disconnects a link that is up.
    assertEquals(ManagementAct.Disconnect, (attachedHome.keys("i", "ArrowDown", "Enter").press(Key("d")).command as Command.Perform).act)

    // Connector: [C] records the word, and only while Unconfirmed.
    val unconfirmed = attached(connectorUnconfirmed = true).keys("i", "ArrowDown", "ArrowDown", "Enter")
    assertEquals(ManagementAct.AcknowledgeConnector, (unconfirmed.press(Key("C")).command as Command.Perform).act)
    assertNull(attachedHome.keys("i", "ArrowDown", "ArrowDown", "Enter").press(Key("C")).command)
  }

  @Test
  fun `only a tunnel problem banners on Workspaces, and the connector does not`() {
    assertTrue("Tunnel needs attention" in attached(state = RuntimeState.Failed(null)).copy(page = Page.Workspaces).prose())
    assertTrue(
      "Tunnel needs attention" in attached(state = RuntimeState.Connecting, connectingWords = ConnectingWords(null, null))
        .copy(page = Page.Workspaces).prose(),
    )
    val recorded = attached(connectorUnconfirmed = false).copy(page = Page.Workspaces).prose()
    assertFalse("needs attention" in recorded, recorded)
    // The connector is still legible on the home screen: the status line, and no banner.
    val unconfirmed = attached(connectorUnconfirmed = true).copy(page = Page.Workspaces).prose()
    assertFalse("needs attention" in unconfirmed, unconfirmed)
    assertTrue("Connector · Unconfirmed" in unconfirmed)
  }

  @Test
  fun `stopping the Runtime is confirmed first`() {
    val asked = attached().keys("i", "Enter").press(Key("X"))
    assertNull(asked.command)
    assertTrue("left Uncertain" in asked.home.prose())
    assertEquals(ManagementAct.Stop, (asked.home.press(Key("y")).command as Command.Perform).act)
  }

  @Test
  fun `acknowledging acts on the selected entry and only on one that needs it`() {
    val uncertain = entry("run_command", ActivityOutcome.Uncertain("stopped"))
    val home = attached(activity = listOf(entry("read_file", ok()), uncertain)).keys("ArrowUp")
    assertEquals(ManagementAct.Acknowledge(uncertain.id), (home.press(Key("a")).command as Command.Perform).act)
    assertNull(home.keys("ArrowUp").press(Key("a")).command)
  }

  @Test
  fun `a change on the stream moves the screen on from its snapshot`() {
    val home = attached()
    val opened = entry("read_file", ActivityOutcome.InFlight, arguments = "path=new")
    val moved = home
      .observed(RuntimeEvent.Change.EntryRecorded(opened))
      .observed(RuntimeEvent.Change.WorkspaceChanged(workspace("notes", AccessLevel.Write)))
    assertTrue(moved.screen().any { "path=new" in it && "in flight" in it })
    assertTrue(moved.copy(page = Page.Workspaces).screen().any { "notes · Write" in it })
  }

  @Test
  fun `a fresh install reads Unconfirmed on the Connector row, and Review carries the instructions`() {
    val fresh = Home().observed(RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Connecting, now), emptyList(), start = thisStart))
    assertTrue("Connector · Unconfirmed" in fresh.prose())
    assertFalse("Delete the app" in fresh.prose())
    assertTrue("No workspaces yet" in fresh.prose())
    val prose = fresh.keys("i", "ArrowDown", "ArrowDown", "Enter").prose()
    for (step in listOf("Delete the app in ChatGPT", "Create MCP App", "Connection: Tunnel", "No authentication, not the OAuth default")) assertTrue(step in prose)
  }

  @Test
  fun `acknowledging the connector records the user's word and clears the row and the status`() {
    val home = attached(connectorUnconfirmed = true, activity = listOf(entry("read_file", ok(), arguments = "path=a")))
    // Not a mode: the feed takes its keys as ever while the connector is Unconfirmed.
    assertNotNull(home.keys("ArrowUp").selected)
    assertTrue("Connector · Unconfirmed" in home.prose())

    val step = home.keys("i", "ArrowDown", "ArrowDown", "Enter").press(Key("C"))
    val performed = step.command as Command.Perform
    assertEquals(ManagementAct.AcknowledgeConnector, performed.act)
    val done = step.home.say(performed.done).observed(RuntimeEvent.Change.ConnectorChanged(unconfirmed = false))
    val said = done.prose()
    assertTrue("your word" in said && "not a measurement" in said, said)
    val list = done.keys("Escape")
    assertTrue("Connector · Confirmed" in list.prose())
    assertFalse("Unconfirmed" in list.screen().joinToString(" "), "the row outlived the acknowledgement")
    assertNull(
      attached().keys("i", "ArrowDown", "ArrowDown", "Enter").press(Key("C")).command,
      "acknowledged a connector that is not Unconfirmed",
    )
  }

  @Test
  fun `the credential panel waits for one long-poll wait, quotes the tunnel, and never diagnoses`() {
    val complaint = TunnelComplaint(
      401, "tunnel_use_forbidden", "Verify the tunnel ID and that the API key has permission to use this tunnel.", "Tunnel use forbidden for this key.",
    )
    assertFalse(attached(state = RuntimeState.Connecting).prose().contains("Only you can check"), "a panel at Start, before any wait")

    val home = attached(state = RuntimeState.Connecting, connectingWords = ConnectingWords(complaint, failureCategory = null))
    val list = home.keys("i", "ArrowDown").screen()
    assertTrue(list.any { "Tunnel · Connecting" in it }, "the Tunnel row reads Connecting: ${list.joinToString("\n")}")
    val screen = home.keys("i", "ArrowDown", "Enter").screen()
    assertTrue(screen.any { "Only you can check" in it })
    val prose = home.keys("i", "ArrowDown", "Enter").prose()
    assertTrue("status_code 401" in prose, prose)
    assertTrue("mitigation \"Verify the tunnel ID and that the API key has permission to use this tunnel.\"" in prose, prose)
    for (check in listOf("the tunnel ID matches the tunnel you created", "the runtime key has not been revoked", "the key belongs to that tunnel")) {
      assertTrue(check in prose, "missing '$check': $prose")
    }
    for (diagnosis in listOf("wrong", "invalid", "incorrect", "bad key")) assertFalse(diagnosis in prose.lowercase(), "it diagnosed: $prose")
    assertFalse("failure_category" in prose, "a category the tunnel never gave")

    val categorised = attached(
      state = RuntimeState.Connecting,
      connectingWords = ConnectingWords(complaint, "http_error"),
    ).keys("i", "ArrowDown", "Enter").prose()
    assertTrue("failure_category \"http_error\"" in categorised, categorised)

    val later = home.observed(RuntimeEvent.Change.RuntimeChanged(RuntimeStatus(RuntimeState.Connected, now)))
    val settled = later.keys("i", "ArrowDown", "Enter").prose()
    assertFalse("Only you can check" in settled, "the panel outlived Connecting")
    assertTrue(Wording.CONNECTED_CAVEAT in settled)
  }

  private fun command(
    id: String,
    command: String,
    workspace: String = "scripts",
    startedAgo: Long = 133,
    promoted: Boolean = false,
    stopping: StopPhase? = null,
  ) = RunningOperation(
    ActivityEntryId(id), Origin.ChatGpt, workspace, "run_command", "command=$command cwd=. request_id=k-$id",
    now.minusSeconds(startedAgo), promoted, stopping,
  )

  private fun output(command: String, text: String, dropped: Int = 0) = RunningCommand(command, ".", kotlin.time.Duration.ZERO, text, dropped)

  private val scripts = workspace("scripts", AccessLevel.Command)

  @Test
  fun `the band is absent when nothing runs, and the screen changes shape when a command starts`() {
    val quiet = attached(workspaces = listOf(scripts))
    assertFalse(quiet.screen().any { it.startsWith("Running") }, "no placeholder line for nothing running")

    val started = quiet.observed(RuntimeEvent.Change.OperationStarted(command("a", "make test")))
    val screen = started.screen()
    val band = screen.indexOfFirst { it.startsWith("Running") }
    assertTrue(band in 1 until screen.indexOfFirst { it.startsWith("Activity ·") }, screen.joinToString("\n"))
    assertTrue("`make test`" in screen[band + 1] && "running 2m13s" in screen[band + 1], screen[band + 1])

    // Only commands: a read in flight is over before anybody could watch it.
    val read = quiet.observed(RuntimeEvent.Change.OperationStarted(RunningOperation(ActivityEntryId("r"), Origin.ChatGpt, "scripts", "read_file", "path=x", now)))
    assertFalse(read.screen().any { it.startsWith("Running") })
    assertFalse(started.observed(RuntimeEvent.Change.OperationEnded(ActivityEntryId("a"))).screen().any { it.startsWith("Running") }, "gone when it ends")
  }

  @Test
  fun `the band holds at the cap and the feed keeps its rows even with a row expanded`() {
    val small = Frame(columns = 120, rows = 24, zone = ZoneOffset.UTC, now = now)
    val running = (1..Operation.COMMAND_CONCURRENCY_CAP).map { command("c$it", "build $it") }
    var home = attached(workspaces = listOf(scripts), running = running, activity = (1..30).map { entry("read_file", ok(), arguments = "path=f$it") })
    home = home.read(ActivityEntryId("c1"), output("build 1", (1..500).joinToString("\n") { "line $it" } + "\n")).keys("Tab")

    val screen = render(home, small).map { it.plain }
    val band = screen.indexOfFirst { it.startsWith("Running") }
    val feed = screen.indexOfFirst { it.startsWith("Activity ·") }
    assertTrue(feed - band <= 1 + Operation.COMMAND_CONCURRENCY_CAP + 1 + small.rows / 4, "the band grew past its bound:\n${screen.joinToString("\n")}")
    assertTrue(screen.drop(feed + 1).count { "path=f" in it } >= 3, "the feed was crowded out:\n${screen.joinToString("\n")}")
  }

  @Test
  fun `a band row carries its last line, and the selected one expands to the buffer get_result returns, labelled`() {
    val buffer = "compiling a\ncompiling b\ntesting c\n"
    val home = attached(workspaces = listOf(scripts), running = listOf(command("a", "make", promoted = true)))
      .read(ActivityEntryId("a"), output("make", buffer, dropped = 4096))
    val row = home.screen().single { "`make`" in it }
    assertTrue(row.endsWith("│ testing c"), row)
    assertFalse(home.screen().any { it.trim() == "│ compiling a" }, "collapsed shows one line")

    val open = home.keys("Tab")
    val screen = open.screen()
    val label = screen.single { "┌" in it }
    assertTrue("the buffer get_result returns for Handle a" in label, label)
    assertTrue("3 lines" in label && "4096 bytes dropped from its middle" in label && "all shown" in label, label)
    assertEquals(listOf("compiling a", "compiling b", "testing c"), screen.filter { it.trimStart().startsWith("│") }.map { it.trim().removePrefix("│ ") })
  }

  @Test
  fun `the cursor walks band and feed in the order they are drawn`() {
    val home = attached(
      workspaces = listOf(scripts),
      running = listOf(command("a", "first-cmd"), command("b", "second-cmd")),
      activity = listOf(entry("read_file", ok(), arguments = "path=oldest"), entry("read_file", ok(), arguments = "path=newest")),
    )
    fun Home.at(): String = screen()[selectedLine()]
    val walked = generateSequence(home.keys("ArrowUp")) { it.keys("ArrowUp") }.take(5).toList()
    assertEquals(listOf("path=newest", "path=oldest", "second-cmd", "first-cmd", "first-cmd"), walked.map { h -> listOf("path=newest", "path=oldest", "second-cmd", "first-cmd").first { it in h.at() } })
    for ((below, above) in walked.zipWithNext().take(3)) assertTrue(above.selectedLine() < below.selectedLine(), "up went down: ${below.at()} → ${above.at()}")
    assertTrue("path=oldest" in walked[3].keys("ArrowDown", "ArrowDown").at(), "down from the band's last row is the feed's first")
    assertTrue("first-cmd" in home.keys("Tab").at(), "tab goes to the band")
  }

  @Test
  fun `stopping is s then y, and the confirmation says what is being ended and what is not undone`() {
    val home = attached(workspaces = listOf(scripts), running = listOf(command("a", "make deploy")))
    val asked = home.press(Key("s"))
    assertNull(asked.command, "nothing is stopped on one key")
    val prose = asked.home.prose()
    for (said in listOf("`make deploy`", "'scripts'", "started 11:57:47", "running 2m13s", "full authority of your Linux account", "Uncertain", "nothing is rolled back")) {
      assertTrue(said in prose, "missing '$said': $prose")
    }
    assertEquals(ManagementAct.StopOperation(ActivityEntryId("a")), (asked.home.press(Key("y")).command as Command.Perform).act)
    for (other in listOf("n", "s", "Enter", "Escape")) {
      val cancelled = asked.home.press(Key(other))
      assertNull(cancelled.command, "'$other' stopped it")
      assertNull(cancelled.home.overlay)
    }
  }

  @Test
  fun `with several running, s asks for a selection rather than guessing`() {
    val home = attached(workspaces = listOf(scripts), running = listOf(command("a", "one"), command("b", "two")))
    assertNull(home.press(Key("s")).home.overlay)
    assertEquals(Overlay.StopCommand(ActivityEntryId("b")), home.keys("Tab", "ArrowDown").press(Key("s")).home.overlay)
  }

  @Test
  fun `a stopping command stays in the band with its phase, and there is no second stop`() {
    val home = attached(workspaces = listOf(scripts), running = listOf(command("a", "make", stopping = StopPhase.Terminating)))
    val row = home.screen().single { "`make`" in it }
    assertTrue("TERM sent" in row && "there is no second, harder stop" in row, row)
    val again = home.press(Key("s"))
    assertNull(again.command)
    assertNull(again.home.overlay)
    assertTrue("already stopping — there is no second, harder stop" in again.home.prose())
    val killing = home.observed(RuntimeEvent.Change.OperationStopping(ActivityEntryId("a"), StopPhase.Killing))
    assertTrue("SIGKILL sent" in killing.screen().single { "`make`" in it })
  }

  @Test
  fun `lowering a level under a running command says it was not stopped, and for a promoted one that its result cannot be collected`() {
    val lowered = listOf(workspace("scripts", AccessLevel.Read))
    val inCall = attached(workspaces = lowered, running = listOf(command("a", "make test"))).keys("Tab").prose()
    assertTrue("did not stop `make test`, started 11:57:47" in inCall && "press [s]" in inCall, inCall)
    assertFalse("can no longer be collected" in inCall, inCall)

    val promoted = attached(workspaces = lowered, running = listOf(command("a", "make test", promoted = true))).keys("Tab").prose()
    assertTrue("did not stop `make test`" in promoted, "it must say it was not stopped: $promoted")
    assertTrue("can no longer be collected" in promoted, "it must say the result cannot be collected: $promoted")

    assertFalse("did not stop" in attached(workspaces = listOf(scripts), running = listOf(command("a", "make test"))).keys("Tab").prose(), "nothing was lowered")
  }

  @Test
  fun `consecutive get_result polls of one Handle draw as one counted row, and every poll stays in the record`() {
    val polls = (1..7).map { entry("get_result", ok(), arguments = "handle=h1") }
    val refused = entry("get_result", ActivityOutcome.Failed("needs Command"), arguments = "handle=h1")
    val home = attached(activity = polls + refused + entry("get_result", ok(), arguments = "handle=h2"))
    assertEquals(9, home.feed.size, "the record is untouched")
    val rows = home.screen().filter { "get_result" in it }
    assertEquals(3, rows.size, rows.joinToString("\n"))
    assertTrue("get_result ×7" in rows[0], rows[0])
    assertTrue("failed" in rows[1] && "×" !in rows[1], "a refused poll breaks the run: ${rows[1]}")
  }

  @Test
  fun `a selected Undelivered entry says ChatGPT saw a failure, does not know, and that nothing is retried`() {
    val home = attached(activity = listOf(entry("edit_file", ActivityOutcome.Undelivered(ok()), arguments = "path=x"))).keys("ArrowUp")
    val prose = home.prose()
    assertTrue("ChatGPT saw a failure and does not know the change was made" in prose, prose)
    assertTrue("Nothing is replayed or retried" in prose, prose)
  }

  @Test
  fun `the detail pane renders the eleven tools against the level, each permitted or not and why`() {
    val catalog = listOf("list_workspaces" to null, "read_file" to AccessLevel.Read, "write_file" to AccessLevel.Write, "run_command" to AccessLevel.Command, "get_result" to AccessLevel.Command)
      .map { (name, level) -> OperationSpec(name, "$name does its thing.", level, emptyList()) }
    val home = Home().observed(
      RuntimeEvent.Snapshot(listOf(workspace("site", AccessLevel.Write)), RuntimeStatus(RuntimeState.Connected, now), emptyList(), start = thisStart, connectorUnconfirmed = false, catalog = catalog),
    ).keys("m", "w")
    val screen = home.screen()
    fun line(tool: String) = screen.single { Regex("[✓✗] $tool\\b").containsMatchIn(it) }
    // Each marked, and each reason naming the level it needs against the level the Workspace is at.
    for ((tool, admitted, needs) in listOf(Triple("read_file", true, "Read"), Triple("write_file", true, "Write"), Triple("run_command", false, "Command"))) {
      assertTrue((if (admitted) "✓ $tool" else "✗ $tool") in line(tool), line(tool))
      assertTrue("needs $needs" in line(tool) && "at Write" in line(tool), line(tool))
    }
    assertTrue("✓ list_workspaces" in line("list_workspaces"))
    val prose = home.keys("ArrowDown", "ArrowDown", "ArrowDown").prose()
    // The only place the authority wording survives once the raise-to-Command banner is gone.
    assertTrue("full authority of your Linux account" in prose && "~/.ssh" in prose && "no per-call prompt" in prose, prose)
    assertTrue("[t] try it" in prose, prose)
  }

  @Test
  fun `trying from the pane asks each argument and runs the same Operation ChatGPT would`() {
    val catalog = listOf(
      OperationSpec("read_file", "Reads.", AccessLevel.Read, listOf(
        ArgumentSpec("workspace", ArgumentType.Text, true, "w"), ArgumentSpec("path", ArgumentType.Text, true, "the file"),
        ArgumentSpec("offset", ArgumentType.Integer, false, "o"), ArgumentSpec("limit", ArgumentType.Integer, false, "l"),
      )),
    )
    val home = Home().observed(RuntimeEvent.Snapshot(listOf(workspace("site")), RuntimeStatus(RuntimeState.Connected, now), emptyList(), start = thisStart, catalog = catalog))
    val typed = home.keys("m", "w", "t", "a", ".", "m", "d", "Enter", "Enter")
    val step = typed.press(Key("Enter"))
    assertEquals(Command.Try(Operation.ReadFile("site", "a.md"), "read_file", "site"), step.command)
    assertIs<Overlay.WorkspaceDetail>(step.home.overlay, "the answer lands in the same view")

    val missing = home.keys("m", "w", "t").press(Key("Enter"))
    assertNull(missing.command)
    assertTrue("'path' is required; nothing was tried." in missing.home.prose())
  }

  private fun ok() = ActivityOutcome.Ok("done")
}
