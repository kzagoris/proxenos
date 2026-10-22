#!/usr/bin/env python3
"""Exercise boot registration against a real user manager, ONLY inside Omabox.

PROXENOS_TREE=/path/to/unpacked/release python3 -u tui/drive-startup.py

Omabox has no logind. A fixture loginctl models authorization and lingering; systemctl,
the installed Runtime, the management socket and TUI input are real. This does not prove
startup before login or a reboot. The fixture and start-gui script are left in the box HOME
for native GUI checks. Run in a dedicated box, never the user's desktop.
"""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import threading
import time

spec = importlib.util.spec_from_file_location("tui_drive", Path(__file__).with_name("drive.py"))
tui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tui)


def wait_for(label, predicate, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        threading.Event().wait(0.05)
    raise AssertionError(label)


def main():
    if Path.home() != Path("/home/sbx") or not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
        raise SystemExit("Run only inside a dedicated Omabox with --systemd; refusing this environment.")
    root = Path.home() / "startup-acceptance"
    if root.exists():
        raise SystemExit(f"Use a fresh box or remove only this test's previous fixture: {root}")
    root.mkdir()
    # Exercise systemd's parser, not just our own quoted-string output.
    tree = root / 'release with spaces % $literal "quote'
    shutil.copytree(os.environ["PROXENOS_TREE"], tree)
    tui.TUI, tui.RUNTIME = str(tree / "bin/tui"), str(tree / "bin/runtime")
    runtime_script = Path(tui.RUNTIME)
    first, rest = runtime_script.read_text().split("\n", 1)
    runtime_script.write_text(first + "\nexec >> " + shlex.quote(str(root / "runtime-launch.log")) + " 2>&1\n" + rest)
    helpers = root / "helpers"
    helpers.mkdir()
    linger = root / "lingering"
    denied = root / "deny-lingering"
    loginctl = helpers / "loginctl"
    loginctl.write_text("#!/bin/sh\ncase \"$1\" in\n"
                       f"show-user) if [ -f {shlex.quote(str(linger))} ]; then echo yes; else echo no; fi;;\n"
                       f"enable-linger) if [ -f {shlex.quote(str(denied))} ]; then echo 'Authorization denied' >&2; exit 1; fi; touch {shlex.quote(str(linger))};;\n"
                       "*) exit 64;;\nesac\n")
    loginctl.chmod(0o700)
    tunnel = helpers / "tunnel-client"
    tunnel.write_text("#!/bin/sh\nexec sleep 600\n")
    tunnel.chmod(0o700)
    config = root / "configuration/proxenos"
    config.mkdir(parents=True)
    real_systemctl = shutil.which("systemctl")
    systemctl = helpers / "systemctl"
    systemctl.write_text("#!/usr/bin/python3\nimport hashlib,json,subprocess,sys\nfrom pathlib import Path\n"
        f"result=subprocess.run([{real_systemctl!r},*sys.argv[1:]],capture_output=True)\n"
        f"base=Path({str(config.parent / 'systemd/user')!r})\n"
        "files={}\nfor name in ('proxenos.service','proxenos-startup.properties'):\n"
        " p=base/name\n if p.exists(): files[name]=hashlib.sha256(p.read_bytes()).hexdigest()\n"
        f"if (base/'proxenos.service').exists(): Path({str(root / 'generated.service')!r}).write_bytes((base/'proxenos.service').read_bytes())\n"
        f"with open({str(root / 'systemctl.jsonl')!r},'a') as log:\n"
        " log.write(json.dumps(dict(args=sys.argv[1:],code=result.returncode,stdout=result.stdout.decode(),stderr=result.stderr.decode(),files=files))+'\\n')\n"
        "sys.stdout.buffer.write(result.stdout)\nsys.stderr.buffer.write(result.stderr)\nsys.exit(result.returncode)\n")
    systemctl.chmod(0o700)
    credentials = config / "credentials"
    credentials.write_text("TUNNEL_ID=fixture-startup\nRUNTIME_KEY=fixture-startup\n")
    credentials.chmod(0o600)
    env = dict(os.environ, PATH=str(helpers) + ":" + os.environ["PATH"],
               XDG_CONFIG_HOME=str(config.parent), XDG_STATE_HOME=str(root / "state"),
               PROXENOS_RUNTIME=tui.RUNTIME)
    # Registration must preserve a tunnel executable discovered only on the shell PATH.
    env.pop("PROXENOS_TUNNEL_CLIENT", None)
    exports = "".join(f"export {key}={shlex.quote(env[key])}\n" for key in
                      ("PATH", "XDG_CONFIG_HOME", "XDG_STATE_HOME", "PROXENOS_RUNTIME"))
    gui = root / "start-gui"
    gui.write_text("#!/bin/sh\n" + exports + "exec " + shlex.quote(str(tree / "bin/gui")) + "\n")
    gui.chmod(0o700)
    control = str(Path(env["XDG_RUNTIME_DIR"]) / "proxenos/control.sock")
    prefix = "io.github.kzagoris.proxenos."

    def request(payload):
        with socket.socket(socket.AF_UNIX) as peer:
            peer.settimeout(80)
            peer.connect(control)
            peer.sendall((json.dumps(payload) + "\n").encode())
            return json.loads(peer.makefile("rb").readline())

    def snapshot():
        return request({"@type": prefix + "control.Request.Observe"})["event"]

    def act(kind, **values):
        reply = request({"@type": prefix + "control.Request.Perform", "act": {
            "@type": prefix + "coreapi.ManagementAct." + kind, **values}})
        assert reply["@type"].endswith(".Done"), reply
        return reply["result"]

    def status():
        result = subprocess.run([tui.TUI, "status", "--json"], env=env, capture_output=True, text=True, timeout=40)
        assert result.returncode == 0, result.stderr
        return json.loads(result.stdout)

    def unit(property):
        return subprocess.check_output(["systemctl", "--user", "show", "proxenos.service",
                                        "--property=" + property, "--value"], text=True).strip()

    def detail(session):
        session.press("\x1b", "\x1b", "i", "\r")

    session = tui.Session(env)
    try:
        session.until("initial Runtime attaches", lambda lines: any("Runtime · Attached" in line for line in lines))
        original = snapshot()["start"]
        assert status()["startup"]["state"] == "Off"
        notes = root / "notes"
        notes.mkdir()
        workspace = act("Register", root=str(notes), name="notes")
        act("SetLevel", id=workspace["id"], level="Command")
        act("Disconnect")
        # Start real work before enablement; its identity must survive both local actions.
        def operation():
            try:
                act("TryOperation", op={"@type": prefix + "coreapi.Operation.RunCommand",
                    "workspace": "notes", "command": "sleep 90", "deliveryKey": "startup-acceptance"})
            except (OSError, ValueError):
                pass  # Explicit Stop ends the socket and work later in this scenario.
        worker = threading.Thread(target=operation, daemon=True)
        worker.start()
        running = wait_for("Operation starts", lambda: snapshot()["running"])
        detail(session)
        denied.touch()
        session.press("e", "y")
        session.until("authorization denial is shown", lambda lines: any("Authorization denied" in line for line in lines))
        assert snapshot()["start"] == original
        assert status()["startup"]["enabled"] is False
        denied.unlink()
        detail(session)
        session.press("e", "y")
        wait_for("boot startup enabled", lambda: status()["startup"]["state"] == "On")
        assert snapshot()["start"] == original
        assert snapshot()["running"][0]["entry"] == running[0]["entry"]
        print("ok: denial recovers; enable preserves detached Runtime and running Operation", flush=True)
        detail(session)
        session.press("d")
        wait_for("boot startup disabled", lambda: status()["startup"]["state"] == "Off")
        assert snapshot()["start"] == original
        assert snapshot()["running"][0]["entry"] == running[0]["entry"]
        assert linger.exists()
        print("ok: disable preserves Runtime, Operation and account lingering", flush=True)
        session.press("\x1b", "\x1b", "q")
        session.pump(0.5)
        os.waitpid(session.pid, 0)
        assert snapshot()["start"] == original
        subprocess.run([tui.TUI, "stop"], env=env, check=True, capture_output=True, timeout=30)
        wait_for("detached Runtime releases socket", lambda: not Path(control).exists())
        subprocess.run([tui.TUI, "start"], env=env, check=True, capture_output=True, timeout=40)
        pid = int(unit("MainPID"))
        assert pid > 0 and unit("ActiveState") == "active"
        fresh = snapshot()
        assert fresh["start"] != original
        assert status()["tunnel"]["state"] == "Disconnected"
        assert status()["workspaces"][0]["accessLevel"] == "Command"
        print("ok: disabled boot startup permits supervised manual Start; saved state survives", flush=True)
        subprocess.run([tui.TUI, "stop"], env=env, check=True, capture_output=True, timeout=30)
        wait_for("supervised Stop settles", lambda: unit("ActiveState") == "inactive")
        assert unit("MainPID") == "0"
        subprocess.run([tui.TUI, "start"], env=env, check=True, capture_output=True, timeout=40)
        before_crash = int(unit("MainPID"))
        subprocess.run(["systemctl", "--user", "kill", "--kill-whom=main", "--signal=KILL", "proxenos.service"], check=True)
        wait_for("crash is restarted", lambda: unit("ActiveState") == "active" and int(unit("MainPID")) not in (0, before_crash), seconds=45)
        wait_for("restarted Runtime answers", lambda: status()["runtime"] == "running")
        print("ok: explicit Stop stays stopped; a crash is restarted", flush=True)
        # Leave a healthy, stopped fixture for Luna's separate native GUI scenarios.
        subprocess.run([tui.TUI, "stop"], env=env, check=True, capture_output=True, timeout=30)
        print(f"fixture: {root}; native GUI: {gui}", flush=True)
        print("LIMITATION: loginctl was a fixture; no actual pre-login boot was tested.", flush=True)
    except BaseException as failure:
        session.pump(0.3)
        print("--- TUI at failure ---\n" + session.screen.text(), flush=True)
        if isinstance(failure, subprocess.CalledProcessError):
            print("--- failed command output ---", failure.stdout, failure.stderr, flush=True)
        if (root / "runtime-launch.log").exists():
            print("--- Runtime launch diagnostics ---\n" + (root / "runtime-launch.log").read_text(), flush=True)
        try:
            print("--- status at failure ---\n" + json.dumps(status(), indent=2), flush=True)
        except Exception as diagnostic:
            print("status unavailable:", diagnostic, flush=True)
        raise
    finally:
        try:
            os.kill(session.pid, 9)
        except ProcessLookupError:
            pass


if __name__ == "__main__":
    main()
