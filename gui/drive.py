#!/usr/bin/env python3
"""Check unpacked bin/gui against the TUI's fixture Runtime.

PROXENOS_TREE=/path/to/unpacked/proxenos xvfb-run -a python3 gui/drive.py

G21/G22 need Xvfb, xauth and xprop. --closes 20 also needs python-xlib to send actual
window-close and confirmation input. It holds Stop's reply at a fixture socket, so closing
does not deliberately Stop the fixture Runtime, and a real TUI stays attached throughout.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import select
import socket
import subprocess
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location("tui_drive", Path(__file__).resolve().parents[1] / "tui/drive.py")
tui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tui)


def until(what, predicate, seconds=15):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.05)
    raise AssertionError(what)


class DelayedStop:
    def __init__(self, at, runtime):
        self.runtime = runtime
        self.pending = threading.Event()
        self.requests = 0
        self.socket = socket.socket(socket.AF_UNIX)
        self.socket.bind(str(at))
        self.socket.listen()
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            try:
                peer, _ = self.socket.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(peer,), daemon=True).start()

    def handle(self, peer):
        with peer:
            request = peer.makefile("rb").readline()
            if not request:
                return
            if b"Perform" in request:
                self.requests += 1
                self.pending.set()
                while peer.recv(1):
                    pass
                return
            with socket.socket(socket.AF_UNIX) as upstream:
                upstream.connect(str(self.runtime))
                upstream.sendall(request)
                while True:
                    ready, _, _ = select.select([peer, upstream], [], [], 1)
                    if peer in ready and not peer.recv(1):
                        return
                    if upstream in ready:
                        data = upstream.recv(65536)
                        if not data:
                            return
                        try:
                            peer.sendall(data)
                        except OSError:
                            return


def closes(gui, env, runtime_socket, count, output):
    from Xlib import X, XK, display, protocol
    from Xlib.ext import xtest

    x = display.Display()
    delayed = DelayedStop(Path(env["HOME"]) / "delayed.sock", runtime_socket)
    delayed_env = dict(env, PROXENOS_CONTROL_SOCKET=str(Path(env["HOME"]) / "delayed.sock"))
    session = tui.Session(env)
    try:
        session.until("TUI attached", lambda lines: any("Attached" in line for line in lines))

        def window():
            for child in x.screen().root.query_tree().children:
                try:
                    if child.get_wm_name() == "Proxenos" and child.get_attributes().map_state == X.IsViewable:
                        return child
                except Exception:
                    pass

        def key(name):
            code = x.keysym_to_keycode(XK.string_to_keysym(name))
            xtest.fake_input(x, X.KeyPress, code)
            xtest.fake_input(x, X.KeyRelease, code)
            x.sync()

        timings = []
        for index in range(count):
            delayed.pending.clear()
            process = subprocess.Popen([str(gui)], env=delayed_env, stderr=output)
            try:
                w = until("GUI window appears", window)
                assert w.get_wm_class()[1] == "proxenos", w.get_wm_class()
                until("GUI attached", lambda: "Attached" in Path(env["XDG_RUNTIME_DIR"], "proxenos/gui.log").read_text())
                time.sleep(0.4)
                w.set_input_focus(X.RevertToParent, X.CurrentTime)
                x.sync()
                # Click the only act button, with coordinates relative to the fixture window.
                bounds = w.get_geometry()
                xtest.fake_input(x, X.MotionNotify, x=bounds.x + 80, y=bounds.y + 190)
                xtest.fake_input(x, X.ButtonPress, 1)
                xtest.fake_input(x, X.ButtonRelease, 1)
                x.sync()
                time.sleep(0.3)
                # Cancel has initial focus; Tab selects the explicit Stop button.
                key("Tab")
                key("Return")
                until("Stop request reached the fixture", delayed.pending.is_set)
                started = time.monotonic()
                event = protocol.event.ClientMessage(window=w, client_type=x.intern_atom("WM_PROTOCOLS"),
                    data=(32, [x.intern_atom("WM_DELETE_WINDOW"), X.CurrentTime, 0, 0, 0]))
                w.send_event(event)
                x.flush()
                assert process.wait(timeout=10) == 0
                timings.append(time.monotonic() - started)
                assert runtime_socket.exists()
                session.pump(0.1)
                print(f"ok: close {index + 1}/{count}, {timings[-1]:.3f}s", flush=True)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
        assert delayed.requests == count, "an act was retried or replayed"
        session.press("i")
        session.until("TUI still attached after the closes", lambda lines: any("Attached" in line for line in lines))
        # A new window attaches and draws without replaying an earlier act.
        assert subprocess.run([str(gui)], env=dict(delayed_env, PROXENOS_GUI_SMOKE="1"), stderr=output, timeout=20).returncode == 0
        assert delayed.requests == count
        print(f"ok: {count} closes during an act; max {max(timings):.3f}s; Runtime/TUI unaffected; no replay")
    finally:
        delayed.socket.close()
        session.press("q")
        os.close(session.fd)
        os.waitpid(session.pid, 0)
        x.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--closes", type=int, default=0)
    args = parser.parse_args()
    tree = Path(os.environ["PROXENOS_TREE"]).resolve()
    gui = tree / "bin/gui"
    tui.RUNTIME, tui.TUI = str(tree / "bin/runtime"), str(tree / "bin/tui")
    with tempfile.TemporaryDirectory(prefix="proxenos-gui-") as home:
        env = tui.machine(home)
        env.pop("PROXENOS_RUNTIME")
        env.update(JAVA_HOME="/nonexistent")
        for key in ("DISPLAY", "XAUTHORITY"):
            if key in os.environ:
                env[key] = os.environ[key]
        log = Path(env["XDG_RUNTIME_DIR"], "proxenos/gui.log")
        result = subprocess.run([str(gui)], env={k: v for k, v in env.items() if k != "DISPLAY"}, capture_output=True, timeout=15)
        assert result.returncode != 0
        assert len(result.stderr.decode().splitlines()) == 1, result.stderr.decode()
        assert "DISPLAY is not set" in result.stderr.decode()
        assert result.stderr.decode().strip() in log.read_text()
        assert log.stat().st_mode & 0o777 == 0o600
        assert log.parent.stat().st_mode & 0o777 == 0o700
        print("ok: no DISPLAY gives one sentence on stderr and in owner-only gui.log")
        with open(Path(home) / "gui-stderr", "wb") as output:
            # A symlinked launcher and an installation containing spaces must still find runtime.
            linked = Path(home) / "linked gui"
            linked.symlink_to(gui)
            result = subprocess.run([str(linked)], env=dict(env, PROXENOS_GUI_SMOKE="1"), stderr=output, timeout=30)
            assert result.returncode == 0, Path(home, "gui-stderr").read_text()
            assert "Attached" in log.read_text() and "first frame" in log.read_text()
            print("ok: unpacked, symlinked bin/gui starts the Runtime, attaches and draws; JAVA_HOME ignored")
            runtime_socket = Path(env["XDG_RUNTIME_DIR"], "proxenos/control.sock")
            try:
                if args.closes:
                    closes(gui, env, runtime_socket, args.closes, output)
            finally:
                subprocess.run([tui.TUI, "stop"], env=env, capture_output=True, timeout=15)


if __name__ == "__main__":
    main()
