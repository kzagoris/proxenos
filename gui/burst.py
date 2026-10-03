#!/usr/bin/env python3
"""The named burst of GUI-SPEC §14.4, recorded as evidence and never a gate.

  burst.py run WORKSPACE PATH [--acts 1000] [--clients 8] --out burst.json
  burst.py latency burst.json gui.log [--since ISO] [--until ISO]

`run` sends read-only Try acts (read_file PATH in WORKSPACE) from concurrent clients over the
Runtime's control socket, one connection per act as every frontend does, while an observer
counts the stream's events and keeps each new Activity entry's id and the Runtime's `at`. It
fails if any act fails.

`latency` reads a GUI started with PROXENOS_GUI_TRACE=1: each `frame at=… newest=…` line is a
frame that first drew a newer entry. An entry's event-to-frame latency is the first such frame,
drawn after the burst began, whose newest entry is at least as new, less the entry's `at`. That
`at` is when the Runtime opened the entry, before the read ran, so the latency includes the read
itself. --since and --until keep the entries opened in that span: the part of a burst during
which the window was hidden, say.
"""
import argparse
from collections import namedtuple
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import math
import os
from pathlib import Path
import socket
import statistics
import threading
import time

PERFORM = "io.github.kzagoris.proxenos.control.Request.Perform"
OBSERVE = "io.github.kzagoris.proxenos.control.Request.Observe"
DONE = "io.github.kzagoris.proxenos.control.Reply.Done"
TRY = "io.github.kzagoris.proxenos.coreapi.ManagementAct.TryOperation"
READ_FILE = "io.github.kzagoris.proxenos.coreapi.Operation.ReadFile"
OK = "io.github.kzagoris.proxenos.coreapi.Outcome.Ok"
RECORDED = "io.github.kzagoris.proxenos.coreapi.RuntimeEvent.Change.EntryRecorded"

Frame = namedtuple("Frame", "drawn newest entries")


def instant(text):
    """A Kotlin `Instant` (UTC, `Z`) to epoch seconds, keeping microseconds: Python takes no more."""
    whole, _, fraction = text.rstrip("Z").partition(".")
    return datetime.fromisoformat(f"{whole}.{(fraction + '000000')[:6]}+00:00").timestamp()


def connect(request):
    # The Runtime's precedence, less config.toml's `control_socket`, which this does not read.
    path = os.environ.get("PROXENOS_CONTROL_SOCKET") or f"{os.environ['XDG_RUNTIME_DIR']}/proxenos/control.sock"
    peer = socket.socket(socket.AF_UNIX)
    peer.connect(path)
    peer.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode())
    return peer


class Observer:
    """The stream's events, counted, and the entries it records after its snapshot."""

    def __init__(self):
        self.lock = threading.Lock()
        self.events = 0
        self.entries = {}
        self.snapshot = threading.Event()
        threading.Thread(target=self.observe, daemon=True).start()

    def observe(self):
        with connect({"@type": OBSERVE}) as peer:
            for line in peer.makefile("rb"):
                event = json.loads(line).get("event", {})
                with self.lock:
                    self.events += 1
                    if event.get("@type") == RECORDED:
                        entry = event["entry"]
                        # An entry is recorded again when it completes; the first is its opening.
                        self.entries.setdefault(json.dumps(entry["id"], sort_keys=True), entry["at"])
                self.snapshot.set()

    def counts(self):
        with self.lock:
            return self.events, dict(self.entries)


def act(workspace, path):
    request = {"@type": PERFORM, "act": {"@type": TRY, "op": {"@type": READ_FILE, "workspace": workspace, "path": path}}}
    with connect(request) as peer:
        line = peer.makefile("rb").readline()
    if not line:
        raise RuntimeError("the Runtime closed the control socket without answering")
    reply = json.loads(line)
    if reply["@type"] != DONE or reply["result"].get("@type") != OK:
        raise RuntimeError(f"the Try did not read the file: {reply}")


def run(args):
    observer = Observer()
    if not observer.snapshot.wait(10):
        raise SystemExit("no snapshot from the Runtime in 10 s")
    events_before, _ = observer.counts()
    started = time.time()
    with ThreadPoolExecutor(args.clients) as clients:
        for done in [clients.submit(act, args.workspace, args.path) for _ in range(args.acts)]:
            done.result()
    sent = time.time()
    time.sleep(2)  # the stream's tail
    events, entries = observer.counts()
    Path(args.out).write_text(json.dumps({"started": started, "entries": entries}))
    print(f"{args.acts} Try acts from {args.clients} clients in {sent - started:.2f} s; "
          f"{events - events_before} stream events; {len(entries)} new Activity entries")


def latency(args):
    burst = json.loads(Path(args.entries).read_text())
    since = instant(args.since) if args.since else float("-inf")
    until = instant(args.until) if args.until else float("inf")
    ats = sorted(at for at in map(instant, burst["entries"].values()) if since <= at < until)
    frames = []
    for line in Path(args.log).read_text().splitlines():
        if line.startswith("gui: frame at="):
            fields = dict(field.split("=", 1) for field in line[len("gui: "):].split()[1:])
            frame = Frame(instant(fields["at"]), instant(fields["newest"]), int(fields["entries"]))
            # gui.log is appended across runs: only frames drawn since this burst began count.
            if frame.drawn >= burst["started"]:
                frames.append(frame)
    # Both run forward in time, so one pass pairs each entry with the first frame drawing it.
    ms, size, frame = [], 0, iter(frames)
    shown = next(frame, None)
    for at in ats:
        while shown is not None and shown.newest < at:
            shown = next(frame, None)
        if shown is None:
            break
        ms.append(1000 * (shown.drawn - at))
        size = max(size, shown.entries)
    if not ms:
        raise SystemExit("no traced frame drew a burst entry; was the GUI started with PROXENOS_GUI_TRACE=1?")
    ms.sort()
    p95 = ms[math.ceil(0.95 * len(ms)) - 1]
    print(f"{len(ms)} of {len(ats)} entries drawn; event-to-frame p50 {statistics.median(ms):.0f} ms, "
          f"p95 {p95:.0f} ms, max {ms[-1]:.0f} ms; Activity size {size}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    runner = commands.add_parser("run")
    runner.set_defaults(command=run)
    runner.add_argument("workspace")
    runner.add_argument("path")
    runner.add_argument("--acts", type=int, default=1000)
    runner.add_argument("--clients", type=int, default=8)
    runner.add_argument("--out", required=True)
    measure = commands.add_parser("latency")
    measure.set_defaults(command=latency)
    measure.add_argument("entries")
    measure.add_argument("log")
    measure.add_argument("--since")
    measure.add_argument("--until")
    args = parser.parse_args()
    args.command(args)


if __name__ == "__main__":
    main()
