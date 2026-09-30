package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * `run_command` and the one kill, against real child processes and real stub
 * scripts: nothing below the core is abstracted, and the budget, the grace and the inherited
 * environment are configuration instead (ADR 0002).
 */
class CommandsTest {
  @TempDir
  lateinit var temporary: Path

  private val runner = CommandRunner(grace = GRACE)

  /** Reaped at the end of the test whatever it did, so a stub cannot outlive the suite. */
  private val started = mutableListOf<CommandExecution>()

  @AfterTest
  fun reapWhatIsLeft() {
    started.forEach { it.reap() }
  }

  private fun start(script: Path): CommandExecution =
    runner.start(script.toString(), temporary).also { started += it }

  /** A real script on disk, executable, which is what a command runs here. */
  private fun script(name: String, body: String): Path {
    val path = temporary.resolve(name)
    Files.writeString(path, "#!/bin/sh\n$body")
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    return path
  }

  /** The account these commands are recorded in. A real one on disk, like everything else here. */
  private val activity by lazy { Activity(temporary.resolve("activity")) }

  private fun commandRuntime(cap: Int = Operation.COMMAND_CONCURRENCY_CAP) = CommandRuntime(activity, cap)

  private var entries = 0

  private fun runCommandOf(
    command: String,
    directory: Path = temporary,
    budget: Duration = 30.seconds,
    commands: CommandRuntime = commandRuntime(),
  ): Outcome<CommandReply> = runBlocking {
    runCommand(
      Operation.RunCommand("api", command, deliveryKey = "k"),
      Workspace(WorkspaceId("id"), "api", temporary.toString(), AccessLevel.Command),
      directory, Arrival(ActivityEntryId("entry-${entries++}")), runner, budget, commands,
    )
  }

  /** A command that finished inside its own call, which is what almost every command does. */
  private fun finished(outcome: Outcome<CommandReply>): CommandResult =
    assertIs<CommandReply.Finished>(assertIs<Outcome.Ok<CommandReply>>(outcome).value).result

  @Test
  fun `a command is a shell line, run through bin sh -c, in the directory it was given`() {
    val result = finished(runCommandOf("echo one && echo two | tr a-z A-Z; pwd"))
    assertEquals("one\nTWO\n${temporary.toRealPath()}\n", result.output)
    assertEquals(0, result.exitCode)
    assertEquals(0, result.droppedBytes)
    assertEquals(Operation.THIS_DIRECTORY, result.cwd)
  }

  @Test
  fun `a command that exits non-zero is ok, because the Operation ran what it was asked to`() {
    // `failed` carries a guarantee — nothing on disk changed — that a command which ran and
    // exited 1 cannot make. What the command thought of itself is the exit code's to say.
    val result = finished(runCommandOf("echo trouble >&2; exit 3"))
    assertEquals(3, result.exitCode)
    // Standard error is interleaved into the one stream, in the order the command wrote it.
    assertEquals("trouble\n", result.output)
  }

  @Test
  fun `a blank command is refused, and nothing is started`() {
    val refused = assertIs<Outcome.Failed>(runCommandOf("   "))
    assertEquals(Failure.InvalidArgument("command"), refused.reason)
  }

  @Test
  fun `the Root does not confine what a command reads`() {
    // A command at Command level runs with the full authority of the user's Linux
    // account, which is wider than the Root. Mandatory sandboxing was rejected by the user, and
    // this test is what stops it being quietly reintroduced.
    val root = Files.createDirectory(temporary.resolve("project"))
    val outside = Files.writeString(temporary.resolve("outside.txt"), "not in the Root\n")

    assertEquals("not in the Root\n", finished(runCommandOf("cat '$outside'", directory = root)).output)
  }

  @Test
  fun `the tunnel credentials are stripped from the child, and the rest is inherited`() {
    val seeded = CommandRunner(
      grace = GRACE,
      environment = mapOf(
        "CONTROL_PLANE_TUNNEL_ID" to "tunnel-id",
        "CONTROL_PLANE_API_KEY" to "runtime-key",
        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
        "WORKSPACE_DASHBOARD_TEST" to "inherited",
      ),
    )
    val execution = seeded.start("env", temporary).also { started += it }
    assertEquals(0, execution.awaitExit(30.seconds))
    val environment = execution.output().text.lines().toSet()

    // Without this, any command at Command level could read the tunnel credentials out of its
    // own environment and print them into a conversation that leaves the machine.
    assertFalse(environment.any { it.startsWith("CONTROL_PLANE_TUNNEL_ID=") }, "$environment")
    assertFalse(environment.any { it.startsWith("CONTROL_PLANE_API_KEY=") }, "$environment")
    assertContains(environment, "WORKSPACE_DASHBOARD_TEST=inherited")
  }

  @Test
  fun `TERM alone reaps a command that answers it, well inside the grace`() {
    val execution = start(script("polite.sh", "trap 'exit 7' TERM\nsleep 30 &\n$READY\n$IDLE\n"))
    awaitReady(execution)

    val elapsed = timing { assertEquals(emptyList(), execution.reap()) }
    assertTrue(elapsed < GRACE, "TERM was answered, so nothing should have waited out the grace: $elapsed")
  }

  @Test
  fun `a command that calls setsid and traps TERM is reaped by both arms and then by SIGKILL`() {
    // Neither arm alone is sufficient: `left-group` leaves the process
    // group while staying a descendant, and `left-tree` leaves the descendant tree while
    // staying in the group. TERM reaches neither: the ignored disposition is inherited.
    val execution = start(
      script(
        "stubborn.sh",
        """
        trap '' TERM
        setsid sleep 30 & printf 'left-group %s\n' "${'$'}!"
        sh -c 'sleep 30 & printf "left-tree %s\n" "${'$'}!"' &
        sleep 30 & printf 'in-both %s\n' "${'$'}!"
        $READY
        $IDLE
        """.trimIndent() + "\n",
      ),
    )
    awaitReady(execution)
    val pids = pidsIn(execution.captured().text)
    assertEquals(setOf("left-group", "left-tree", "in-both"), pids.keys)

    val elapsed = timing { assertEquals(emptyList(), execution.reap(), "every process should have been reaped") }
    // TERM was ignored, so what reaped these is the grace and then the escalation, not the TERM.
    assertTrue(elapsed >= GRACE, "SIGKILL must follow a full grace, not precede it: $elapsed")
    // `left-tree` is reached by the group's KILL alone, which reap cannot wait on because it has
    // no handle to it: a delivered KILL is given the moment it takes to land, well short of the 30s
    // a missed one would leave it running.
    for ((label, pid) in pids) await("$label (pid $pid) to die", within = 5.seconds) { !alive(pid) }
  }

  @Test
  fun `a process forked after the snapshot and outside the group is named, and said to be unsignalled`() {
    // It is started by the TERM handler of a helper that `setsid` has already taken out of the
    // group, so it exists only after the tree was snapshotted and is born outside the group rather
    // than leaving it after the group's TERM may have landed. Neither arm of the kill can reach
    // it, and rounding it off to "stopped" would be a plain untruth about this machine.
    // Both idle on `wait` alone, which a TERM interrupts: IDLE's `sleep 1` would be one more
    // process born after the snapshot, and the helper's would survive the kill as well.
    val lateOne = script("late.sh", "printf 'late %s\\n' \"${'$'}${'$'}\"\nexec sleep 30\n")
    val helper = script("helper.sh", "trap \"'$lateOne' &\" TERM\nsleep 30 &\n$READY\nwhile true; do wait; done\n")
    val forksLate = script("forks-late.sh", "trap : TERM\nsetsid '$helper' &\nwhile true; do wait; done\n")
    // `exec`, so the shell that outlives TERM is the command itself: a `sh -c` that forks the
    // script would die on the TERM and leave nothing for the late walk to walk.
    val execution = runner.start("exec '$forksLate'", temporary).also { started += it }
    awaitReady(execution)

    // Reap's two halves, with the late fork waited for between them rather than raced against the grace.
    val snapshot = execution.term()
    await("the TERM handler to fork one") { "late" in pidsIn(execution.captured().text) }
    val late = pidsIn(execution.captured().text).getValue("late")
    val survivors = execution.kill(snapshot)
    try {
      assertEquals(listOf(Survivor(late.toLong(), signalled = false)), survivors)
      assertContains(survivors.describe(), "pid $late")
      assertContains(survivors.describe(), "never signalled")
      assertTrue(alive(late), "the point of naming it is that it is still running")
    } finally {
      ProcessHandle.of(late.toLong()).ifPresent { it.destroyForcibly() }
    }
  }

  @Test
  fun `a command that outruns its budget is Promoted rather than killed, and keeps running`() {
    // ADR 0003: killing a command mid-flight manufactures the worst outcome we have, and
    // letting it finish produces something known. So the budget hands back a Handle.
    val commands = commandRuntime()
    val script = script("slow.sh", "printf 'going\\n'; sleep 30\n")
    val promoted = assertIs<CommandReply.Promoted>(
      assertIs<Outcome.Ok<CommandReply>>(
        runCommandOf(script.toString(), budget = 300.milliseconds, commands = commands),
      ).value,
    )

    assertEquals(script.toString(), promoted.running.command)
    // The reply precedes the outcome: there is no exit code here to read, and the command is
    // still running on this machine.
    val handle = Operation.GetResult("api", promoted.handle.value)
    val workspace = Workspace(WorkspaceId("id"), "api", temporary.toString(), AccessLevel.Command)
    val collected = assertIs<Outcome.Ok<Collected>>(commands.collect(handle, workspace))
    assertIs<Collected.StillRunning>(collected.value)

    // The slot it holds is one of the four, and it holds it until it finishes or is stopped.
    runBlocking { commands.stop() }
  }

  @Test
  fun `Runtime Stop leaves a promoted command Uncertain, never Lost`() {
    // The Runtime chose to end it and knows that it did. Lost is reserved for a
    // Runtime taken from the machine.
    val commands = commandRuntime()
    val script = script("trapped.sh", "trap '' TERM\n$READY\n$IDLE\n")
    val promoted = assertIs<CommandReply.Promoted>(
      assertIs<Outcome.Ok<CommandReply>>(
        runCommandOf(script.toString(), budget = 300.milliseconds, commands = commands),
      ).value,
    )

    runBlocking { commands.stop() }

    val workspace = Workspace(WorkspaceId("id"), "api", temporary.toString(), AccessLevel.Command)
    val collected = assertIs<Outcome.Ok<Collected>>(
      commands.collect(Operation.GetResult("api", promoted.handle.value), workspace),
    )
    val uncertain = assertIs<Outcome.Uncertain>(assertIs<Collected.Reached>(collected.value).outcome)
    assertIs<Uncertainty.Stopped>(uncertain.reason)
    assertContains(uncertain.message, Outcome.Uncertain.EFFECTS_UNCERTAIN)
  }

  @Test
  fun `Stop costs one grace period in total, not one per command`() {
    // The kill is applied to every running Operation in parallel. Four commands that
    // each trap TERM would cost four graces if it were not.
    val commands = commandRuntime()
    val script = script("stubborn.sh", "trap '' TERM\n$READY\n$IDLE\n")
    repeat(Operation.COMMAND_CONCURRENCY_CAP) {
      assertIs<Outcome.Ok<CommandReply>>(
        runCommandOf(script.toString(), budget = 300.milliseconds, commands = commands),
      )
    }

    val elapsed = System.nanoTime()
    runBlocking { commands.stop() }
    val took = (System.nanoTime() - elapsed).nanoseconds

    assertTrue(
      took < GRACE * Operation.COMMAND_CONCURRENCY_CAP,
      "Stop took $took, which is more than one grace period for ${Operation.COMMAND_CONCURRENCY_CAP} commands",
    )
  }

  @Test
  fun `after Stop begins, a promoted command is still run to completion and recorded`() {
    // Work is never abandoned: the Operation reaches an outcome whether or not anyone is
    // left to hear it.
    val commands = commandRuntime()
    val promoted = assertIs<CommandReply.Promoted>(
      assertIs<Outcome.Ok<CommandReply>>(
        runCommandOf("printf 'work\\n'; sleep 1", budget = 200.milliseconds, commands = commands),
      ).value,
    )
    val workspace = Workspace(WorkspaceId("id"), "api", temporary.toString(), AccessLevel.Command)

    await("the command to finish") {
      val collected = assertIs<Outcome.Ok<Collected>>(
        commands.collect(Operation.GetResult("api", promoted.handle.value), workspace),
      )
      collected.value is Collected.Reached
    }
    val collected = assertIs<Outcome.Ok<Collected>>(
      commands.collect(Operation.GetResult("api", promoted.handle.value), workspace),
    )
    val result = assertIs<Outcome.Ok<CommandResult>>(assertIs<Collected.Reached>(collected.value).outcome)
    assertEquals(0, result.value.exitCode)
    assertEquals("work\n", result.value.output)
  }

  @Test
  fun `a machine without setsid still runs commands, with the kill down to its tree arm`() {
    // `setsid` is a dependency the design never asked for. Failing every command on a machine
    // without it would be a worse answer than running one with an arm of the kill missing —
    // and the reaping says which processes it could not reach rather than claiming otherwise.
    val without = CommandRunner(newSession = "no-such-program-on-this-machine", grace = GRACE)
    val execution = without.start("printf 'ran\n'; sleep 30", temporary).also { started += it }
    await("the command to run") { "ran" in execution.captured().text.lines() }

    assertEquals(emptyList(), execution.reap())
    assertFalse(execution.awaitExit(5.seconds) == null, "the shell should have been reaped")
  }

  @Test
  fun `the fifth concurrent command is failed, naming the cap, and nothing ran`() {
    val commands = commandRuntime()
    val go = temporary.resolve("go")
    val holding = (1..Operation.COMMAND_CONCURRENCY_CAP).map { index ->
      Thread.ofVirtual().start {
        runCommandOf("touch started-$index; until [ -f '$go' ]; do sleep 0.05; done", commands = commands)
      }
    }
    try {
      await("the cap to be full") { (1..commands.cap).all { Files.exists(temporary.resolve("started-$it")) } }

      val refused = assertIs<Outcome.Failed>(runCommandOf("touch started-5", commands = commands))
      assertEquals(Failure.CommandCapReached(Operation.COMMAND_CONCURRENCY_CAP), refused.reason)
      assertContains(refused.message, "${Operation.COMMAND_CONCURRENCY_CAP} commands are already running")
      // Nothing ran, which is the guarantee doing its job rather than being worked around.
      assertFalse(Files.exists(temporary.resolve("started-5")))
      assertContains(refused.message, "safe to repeat")
    } finally {
      Files.writeString(go, "")
      holding.forEach { it.join(30_000) }
    }
  }

  @Test
  fun `a slot is given back, so the cap bounds what is running rather than what has ever run`() {
    val commands = commandRuntime()
    repeat(commands.cap + 2) { assertEquals(0, finished(runCommandOf("true", commands = commands)).exitCode) }
  }

  @Test
  fun `output past the cap keeps both ends and names the bytes that fell between them`() {
    // The middle is what a long build repeats; the two ends are where the command
    // said what it was doing and how it ended.
    val filler = "x".repeat(60)
    val lines = 2000
    val result = finished(
      runCommandOf("i=0; while [ \$i -lt $lines ]; do printf '%s-%s\\n' \$i $filler; i=\$((i+1)); done"),
    )
    val output = result.output
    val produced = (0 until lines).sumOf { "$it-$filler\n".utf8Size() }

    assertTrue(result.cappedByBytes)
    assertTrue(output.utf8Size() <= Operation.OUTPUT_CAP_BYTES, "${output.utf8Size()} bytes")
    assertTrue(output.startsWith("0-$filler\n"), output.take(80))
    assertTrue(output.endsWith("${lines - 1}-$filler\n"), output.takeLast(80))
    // The marker is a byte count, and it accounts for everything that is not in the two ends.
    assertEquals(produced - output.utf8Size(), result.droppedBytes)
  }

  /** The pids a stub announced, by the label it announced them under. */
  private fun pidsIn(output: String): Map<String, String> = output.lines()
    .mapNotNull { line -> line.split(' ').takeIf { it.size == 2 && it[1].toLongOrNull() != null } }
    .associate { it[0] to it[1] }

  private fun alive(pid: String): Boolean = ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)

  /**
   * Waiting on the stub's own word rather than on a descendant count: a grandchild whose parent
   * has exited is gone from the tree, which is the very case some of these stubs are built on.
   */
  private fun awaitReady(execution: CommandExecution) =
    await("the stub to say it is ready") { READY_LINE in execution.captured().text.lines() }

  private fun await(what: String, within: Duration = 30.seconds, until: () -> Boolean) {
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
      if (until()) return
      Thread.sleep(20)
    }
    fail("Waited $within for $what")
  }

  private fun timing(block: () -> Unit): Duration {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start).nanoseconds
  }

  private companion object {
    /** Short, so a reaping test costs a fraction of a second rather than the real five. */
    val GRACE = 500.milliseconds

    const val READY_LINE = "ready"
    const val READY = "printf '$READY_LINE\\n'"

    /** Still here after its children were signalled, which is what makes a late walk possible. */
    const val IDLE = "while true; do wait; sleep 1; done"
  }
}
