#!/usr/bin/env python3
"""The pty harness for the `tui` artifact.

Drives the installed `tui` through a pseudo-terminal against a real `runtime`, in a throwaway
HOME, and judges what the keys did by reading the screen back — not merely that a key was
accepted. A harness that presses keys without judging direction is exactly what let the
prototype's arrow keys run backwards.

    ./gradlew :runtime:installDist :tui:installDist
    python3 tui/drive.py            # exits non-zero, naming the check, if any check fails

    PROXENOS_TREE=path/to/unpacked/proxenos python3 tui/drive.py

drives an unpacked distribution instead, which is how a release checks the archive it publishes.

It needs no network: a stub stands in for `tunnel-client`, so the Runtime stays Connecting.
"""
import fcntl
import os
import pty
import re
import select
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import termios
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREE = os.environ.get("PROXENOS_TREE")
TUI = os.path.join(TREE, "bin/tui") if TREE else os.path.join(REPO, "tui/build/install/tui/bin/tui")
RUNTIME = os.path.join(TREE, "bin/runtime") if TREE else os.path.join(REPO, "runtime/build/install/runtime/bin/runtime")
COLUMNS = int(os.environ.get("TUI_TEST_COLUMNS", "140"))
ROWS = int(os.environ.get("TUI_TEST_ROWS", "40"))


class Screen:
    """Just enough of a terminal to read Mosaic's frames back: it moves up with CSI n F, erases
    with CSI K and CSI J, and ignores colour."""

    def __init__(self):
        self.lines, self.row, self.col = [""], 0, 0

    def feed(self, text):
        for token in re.findall(r"\x1b\[[0-9;?]*[A-Za-z]|\r|\n|[^\x1b\r\n]+", text):
            if token == "\r":
                self.col = 0
            elif token == "\n":
                self.row += 1
                self._reach()
            elif token.startswith("\x1b["):
                params = token[2:-1]
                n = int(params) if params.isdigit() else 1
                final = token[-1]
                if token == "\x1b[?1049h":
                    self.lines, self.row, self.col = [""], 0, 0
                elif final == "H":
                    self.row, self.col = 0, 0
                elif final == "F":
                    self.row, self.col = max(0, self.row - n), 0
                elif final == "K":
                    self.lines[self.row] = self.lines[self.row][: self.col]
                elif final == "J":
                    del self.lines[self.row + 1:]
                    self.lines[self.row] = self.lines[self.row][: self.col]
            else:
                line = self.lines[self.row].ljust(self.col)
                self.lines[self.row] = line[: self.col] + token + line[self.col + len(token):]
                self.col += len(token)

    def _reach(self):
        while len(self.lines) <= self.row:
            self.lines.append("")

    def text(self):
        return "\n".join(self.lines)


class Session:
    def __init__(self, env):
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.execve(TUI, [TUI], env)
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLUMNS, 0, 0))
        self.screen = Screen()

    def pump(self, seconds):
        end = time.time() + seconds
        while time.time() < end:
            ready, _, _ = select.select([self.fd], [], [], 0.05)
            if ready:
                try:
                    self.screen.feed(os.read(self.fd, 65536).decode("utf-8", "replace"))
                except OSError:
                    return

    def until(self, what, predicate, seconds=30):
        end = time.time() + seconds
        while time.time() < end:
            self.pump(0.2)
            if predicate(self.screen.lines):
                return self.screen.lines
        fail(what, self.screen.text())

    def press(self, *keys):
        for key in keys:
            os.write(self.fd, key.encode())
            self.pump(0.3)


def fail(what, screen=""):
    print(f"FAIL: {what}\n--- screen ---\n{screen}", file=sys.stderr)
    sys.exit(1)


def ok(what):
    print(f"ok: {what}")


def selected(lines):
    """The index of the feed row the cursor is on, and that row."""
    for index, line in enumerate(lines):
        if re.match(r"^[ !]>", line):
            return index, line
    return None, None


def machine(home):
    """A HOME with a credential, a stub tunnel-client on PATH, and an account an earlier Runtime left."""
    config = os.path.join(home, ".config/proxenos")
    os.makedirs(config)
    credentials = os.path.join(config, "credentials")
    with open(credentials, "w") as f:
        f.write("TUNNEL_ID=tunnel_drive\nRUNTIME_KEY=sk-drive\n")
    os.chmod(credentials, 0o600)
    bin_dir = os.path.join(home, "bin")
    os.makedirs(bin_dir)
    stub = os.path.join(bin_dir, "tunnel-client")
    with open(stub, "w") as f:
        f.write("#!/bin/sh\nexec sleep 600\n")
    os.chmod(stub, 0o700)
    state = os.path.join(home, ".local/state/proxenos")
    os.makedirs(state)
    records = []
    for n, path in enumerate(["first.md", "second.md", "third.md"]):
        at = f"2026-09-23T09:0{n}:00Z"
        records.append(f"open\tid=e{n}\tat={at}\tstart=earlier\torigin=ChatGpt\tworkspace=notes\ttool=read_file\targs=path={path}")
        records.append(f"complete\tid=e{n}\tat={at}\toutcome=ok\tdetail=1 line")
    # Opened and never completed by a Runtime that is gone: this start reads it back as Lost.
    records.append("open\tid=lost\tat=2026-09-23T09:05:00Z\tstart=earlier\torigin=ChatGpt\tworkspace=notes\ttool=write_file\targs=path=lost.md")
    with open(os.path.join(state, "activity"), "w") as f:
        f.write("".join(r + "\n" for r in records))
    return {
        "HOME": home,
        "XDG_RUNTIME_DIR": os.path.join(home, "run"),
        "PATH": f"{bin_dir}:/usr/bin:/bin",
        "TERM": "xterm-256color",
        # Without a UTF-8 locale the JVM writes every ─, · and ↑ as "?", as it would on such a terminal.
        "LANG": "C.UTF-8",
        "PROXENOS_RUNTIME": RUNTIME,
        # The one thing taken from the caller's environment: the JDK both launchers run on.
        **({"JAVA_HOME": os.environ["JAVA_HOME"]} if "JAVA_HOME" in os.environ else {}),
    }


def main():
    for launcher in (TUI, RUNTIME):
        if not os.access(launcher, os.X_OK):
            fail(f"{launcher} is not built; run ./gradlew :runtime:installDist :tui:installDist")
    if "JAVA_HOME" not in os.environ and shutil.which("java"):
        os.environ["JAVA_HOME"] = os.path.dirname(os.path.dirname(os.path.realpath(shutil.which("java"))))
    home = tempfile.mkdtemp(prefix="tui-drive-")
    env = machine(home)
    project = os.path.join(home, "notes")
    os.makedirs(project)
    session = Session(env)
    try:
        lines = session.until("the TUI starts the Runtime and attaches", lambda l: any("Tunnel · Connecting" in x for x in l))
        ok("the Runtime was not running, and the TUI started it")
        screen = "\n".join(lines)
        if "lost.md" in screen or "Delete the app" in screen or "an earlier Runtime start" in screen:
            fail("the home screen contains previous activity or long setup instructions", screen)
        session.press("a")
        session.until("Activity opens", lambda l: any("nothing recorded yet" in x for x in l))
        if "lost.md" in session.screen.text() or "1 unresolved" in session.screen.text():
            fail("old unresolved activity leaked into the dashboard", session.screen.text())
        ok("previous activity is absent, including unresolved entries")
        session.press("\x1b", "n", *project, "\r", "\r")
        session.until("registered workspace appears", lambda l: any("notes · Read" in x for x in l))
        if project not in session.screen.text():
            fail("workspace directory is missing", session.screen.text())
        session.press("m", "3")
        prose = " ".join(x.strip() for x in session.screen.lines)
        for needed in ("full authority of your Linux account", "not bounded by the Root", "~/.ssh", "no per-call prompt"):
            if needed not in prose:
                fail(f"raising to Command says '{needed}'", session.screen.text())
        session.press("y")
        session.until("level changes after confirmation", lambda l: any("Access · Command" in x for x in l))
        ok("Manage changes the selected workspace after confirmation")

        # Try a read through the real management seam to create activity in this run.
        with open(os.path.join(project, "current.txt"), "w") as f:
            f.write("current run\n")
        session.press("w")
        # Walk the catalog using the selected tool marker rather than assuming its order.
        for _ in range(20):
            if any(re.match(r"^  > .*read_file\b", x) for x in session.screen.lines):
                break
            session.press("\x1b[B")
        else:
            fail("read_file is reachable in Tools", session.screen.text())
        session.press("t", *"current.txt", "\r", "\r", "\r")
        session.until("Try returns a result", lambda l: any("Tried read_file" in x and "ok." in x for x in l))
        session.press("\x1b", "\x1b", "a")
        session.until("current activity is visible", lambda l: any("read_file" in x and "current.txt" in x for x in l))
        session.press("\x1b[A")
        if selected(session.screen.lines)[1] is None:
            fail("Activity supports selection", session.screen.text())
        ok("current operations appear in Activity and can be selected")

        session.press("\x1b")
        dump = subprocess.run([TUI, "--dump", "--frames", "1"], env=env, capture_output=True, text=True, timeout=60)
        if dump.returncode != 0 or "notes · Command" not in dump.stdout or "lost.md" in dump.stdout or "current.txt" in dump.stdout:
            fail("--dump shows the workspace home screen", dump.stdout + dump.stderr)
        ok("a second frontend shows the same workspace state without activity clutter")

        session.press("i")
        if not all(any(f"{stage} ·" in x for x in session.screen.lines) for stage in ("Runtime", "Tunnel", "Connector")):
            fail("Review lists the three connection stages", session.screen.text())
        session.press("\x1b[B", "\x1b[B", "\r")
        prose = " ".join(x.strip() for x in session.screen.lines)
        if "Delete the app" not in prose:
            fail("the Connector detail exposes setup instructions", session.screen.text())
        ok("Review lists Runtime, Tunnel and Connector, and the Connector detail carries the setup steps")
        session.press("\x1b", "\x1b", "q")
        session.pump(1.0)
        _, status = os.waitpid(session.pid, 0)
        if os.waitstatus_to_exitcode(status) != 0:
            fail("Close dashboard exits", session.screen.text())
        # Reopen against the same Runtime: current-run activity must survive closing the UI.
        session = Session(env)
        session.until("reopened dashboard attaches", lambda l: any("notes · Command" in x for x in l))
        session.press("a")
        session.until("reopening retains this run", lambda l: any("current.txt" in x for x in l))
        ok("closing the dashboard leaves the Runtime and current activity intact")
        session.press("i", "\r", "X", "y")
        session.until("Stop Runtime detaches", lambda l: any("Runtime · Not running" in x for x in l))
        # The control socket closes before the exiting process releases its state lock.
        # Wait for completed shutdown before asking for a fresh Runtime.
        with open(os.path.join(home, ".local/state/proxenos/runtime.lock"), "r+") as lock:
            deadline = time.time() + 30
            while True:
                try:
                    fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    fcntl.lockf(lock, fcntl.LOCK_UN)
                    break
                except BlockingIOError:
                    if time.time() >= deadline:
                        fail("Runtime releases its lock after Stop", session.screen.text())
                    session.pump(0.1)
        session.press("S")
        session.until("Runtime restarts", lambda l: any("Runtime · Attached" in x for x in l))
        session.press("\r")
        if "This Runtime started" not in session.screen.text():
            fail("the Runtime detail names the start time", session.screen.text())
        ok("S starts the stopped Runtime, and the Runtime detail names its start")
        session.press("\x1b", "\x1b", "a")
        session.until("new run has empty activity", lambda l: any("nothing recorded yet" in x for x in l))
        if "current.txt" in session.screen.text():
            fail("previous run survived in the dashboard", session.screen.text())
        activity_file = os.path.join(home, ".local/state/proxenos/activity")
        with open(activity_file) as f:
            if "current.txt" not in f.read():
                fail("Runtime records were deleted")
        ok("Runtime restart resets displayed activity and preserves stored records")
        session.press("q")
        session.pump(1.0)
        os.waitpid(session.pid, 0)
    finally:
        try:
            os.kill(session.pid, signal.SIGKILL)
        except (ProcessLookupError, ChildProcessError):
            pass
        subprocess.run([TUI, "stop"], env=env, capture_output=True, timeout=60)
        shutil.rmtree(home, ignore_errors=True)
    print("all checks passed")


if __name__ == "__main__":
    main()
