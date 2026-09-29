# A command that outruns its call is promoted, not killed

The transport gives one tool call roughly 61 seconds. Measured, not read: a 50-second call
round-trips cleanly, and a 120-second call is delivered a second time at exactly +61s — a
duplicate minted upstream, byte-identical to the original, with the same JSON-RPC id and the
same `_meta`. One prompt was observed executing the same work four times, the last two
dispatched after ChatGPT had already shown the user a timeout. No deadline is forwarded to
the local server, so an implementation cannot read its own remaining budget.

So the Runtime holds a **45-second** budget of its own, and what happens at 45 seconds is the
decision here. A `run_command` Operation that is still running is **Promoted**: the command
keeps going, the call is answered immediately with a **Handle**, and one extra catalog entry,
`get_result`, claims the outcome later. The Runtime never abandons work it has started —
the Operation runs to completion and reaches an outcome whether or not anyone is left to
hear it.

## Considered options

**Fail at the budget and keep the catalog synchronous-only** was the standing decision, and
it is what this replaces. It costs the user every build, every test suite and every large
search, with no remedy inside the product but their own terminal. It also manufactures the
worst outcome we have: killing a command mid-flight produces `uncertain`, while letting it
finish produces something known.

**MCP's Tasks extension** is the standard shape for exactly this and was measured first.
ChatGPT's client declares the `extensions` mechanism and lists only
`io.modelcontextprotocol/ui` beside it, while negotiating the protocol version Tasks arrived
in. A tool declaring `execution.taskSupport` as `optional` is called plainly; one declaring
it `required` is unusable. The mechanism is unreachable over this transport, so a handle has
to be application-level.

**Ship the handle in a later version**, as originally deferred, was rejected on two grounds.
ChatGPT snapshots a developer-mode app's tool catalog and offers no refresh, and Disconnect
deletes the plugin definition outright — so adding a catalog entry later is not a version
bump but every user deleting and re-creating their connector. And the objection that earned
the deferral was the cost of durable job records surviving restart, which the Activity
decisions have since paid in full: Activity is already append-only, already persists, already
keeps 30 days, and already never expires an unresolved entry until it is Acknowledged. The
Handle points into it rather than founding a second store.

**Letting the caller ask for background execution** was rejected as a worse interface: it
makes the model predict how long its own command will take, and it is wrong about that.
Promotion is automatic.

## Consequences

The catalog grows from ten entries to eleven, breaking the flat ten-tool shape settled
earlier. `get_result` re-checks the Access Level on every call, so dropping a Workspace to
Read cuts off collection of output from a command already running.

A promoted Operation's **reply precedes its outcome**. The three-valued outcome stands
unchanged — every Operation still reaches exactly one of `ok`, `failed` or `uncertain` — but
`Promoted` is a reply, not a fourth outcome, and must never be worded as success.

Cancellation has nowhere to live but locally. ChatGPT's Stop never reaches the server: no
`notifications/cancelled` frame arrived in an entire session, and a call stopped eight
seconds in ran its full 300 seconds. The frontend's stop control is therefore the only stop
button on the machine, and the kill mechanics move from the timeout path onto it — signalling
both the snapshotted descendant tree and the process group, TERM then SIGKILL, since a
command that calls `setsid` escapes the group while staying a descendant and TERM alone never
reaps one that traps it.

Promotion also widens replay exposure: a duplicate minted at +61s against a promoted command
starts a second command unless de-duplication catches it. Because the duplicate is identical
down to `_meta`, the de-duplication key can only travel in the tool arguments.

The 45 seconds is a configured constant, not a derived one. The ceiling it hides under was
measured, is not contractual, and was observed to vary; re-measuring the transport is the way
to re-cut it.
