# Proxenos

Exposes selected local directories to ChatGPT over the OpenAI Secure MCP Tunnel, so a
conversation can read and change work on this machine. Several are exposed at once, and a
local frontend controls what each one allows.

## Language

**Workspace**:
A named registration of one local directory that the user has chosen to expose. Its
identity is a generated id, so a Workspace stays itself when its directory is renamed or
moved.
_Avoid_: Project, folder, directory, location, ChatGPT workspace (an OpenAI account
context, an unrelated thing)

**Root**:
The single directory a Workspace exposes. An attribute of the Workspace, never its
identity, and never a confinement boundary for commands.
_Avoid_: Path, working directory, allowed directory, base folder

**Broken**:
The state of a Workspace whose Root no longer resolves to the directory it was registered
against. A Broken Workspace is absent from discovery and rejects every call until the user
re-confirms it.
_Avoid_: Missing, stale, orphaned, invalid

**Access Level**:
The single dial that says what a Workspace permits, one of None, Read, Write or Command,
each including the ones beneath it. None is how a Workspace is withheld; there is no
separate visibility switch. A withheld Workspace is withheld completely: it is absent from
discovery, and a call that names it is answered as though no Workspace by that name exists,
because an answer that named its level would disclose the very directory the dial is keeping
back. The levels above None are open about themselves — a call that asks for more than its
Workspace allows is told its level and the level it needs.
_Avoid_: Permission, capability, scope, role, grant

**Scoped**:
The state of a Git tool's answer that covers the Root alone, because the Root sits inside a
larger repository: the tool runs at the repository root and is pathspec-scoped to the Root. It
is always said out loud, because an empty `git_status` that did not say it had been Scoped
reads as "the repository is clean" — which is a different, and wrong, fact about the machine.
It is not the sense of the word Access Level rules out: nothing Scoped says what a Workspace
permits, and the two never appear in one sentence.
_Avoid_: Filtered, limited, restricted, confined (that is what §2.4 does to a path argument)

**Command**:
The Access Level at which a Workspace may run programs. It carries the full authority of
the user's Linux account, which is wider than the Root.
_Avoid_: Shell access, execute permission, sandbox

**Runtime**:
The background host that owns every registered Workspace and answers ChatGPT's calls. It
outlives the frontends that configure it, so closing one leaves exposure exactly as it was.
_Avoid_: server, daemon, backend, service, agent

**Attached**:
The state of a frontend that holds the Runtime's management stream. It is the frontend's link,
never the Runtime's life: a Runtime with no frontend Attached keeps running and keeps exposing
exactly what it did, and closing the frontend that is Attached changes nothing. It is never
Connected — that is the tunnel — and says nothing about whether the Runtime can reach OpenAI.
_Avoid_: connected, bound, subscribed, attached Runtime (the Runtime is not the one attached)

**Connected**:
The state in which the Runtime holds its link to the tunnel. It is the strongest claim this
machine can measure, and it is weaker than "ChatGPT can reach the Runtime": a connector deleted
in ChatGPT leaves the link up and the Runtime Connected. It says nothing about what any
Workspace permits either — a Connected Runtime whose Workspaces all sit at None exposes nothing.
_Avoid_: online, live, active, reachable, running (the Runtime runs whether or not it is Connected)

**Connecting**:
The state of a Runtime that has not reached the tunnel once since it Started, or since the user
last Connected it after a Disconnect. It is one-way: a link that has been Connected and is lost
is Failed, never Connecting again, because never having had a link is a different fact from
losing one that worked. A Connect is not a loss — the user took the link down themselves, and
nothing has been measured since they asked for it back.
_Avoid_: reconnecting, retrying, pending, unstable, degraded

**Failed**:
The state of a Runtime whose link to the tunnel worked and has since gone stale. It is never
the user's choice — that is Disconnect, and the two must never read alike. It carries the
tunnel's own last complaint rather than a diagnosis of its own, because nothing on this machine
can tell a deleted tunnel from a rejected key.
_Avoid_: error, down, offline, disconnected, broken (that is a Workspace whose Root moved)

**Disconnect**:
Ending Connected state deliberately, leaving the Runtime and every registration untouched.
Calls fail until it is Connected again. It changes no Access Level and stops no work already
running.
_Avoid_: revoke (that is Access Level at None), stop, pause, shut down

**Stop**:
Ending the Runtime itself. Registrations survive it; nothing is exposed and no frontend can
attach until it runs again. It also ends every Operation still running, by the one kill the
machine has — signal the tree and the group, wait out the grace, then kill — so work in
flight is left Uncertain rather than Lost. The Runtime chose to end it and says so; it did
not vanish out from under it.
_Avoid_: disconnect, quit, kill, exit

**Operation**:
A single invocation of one catalog entry against one named Workspace. It reports one of ok,
failed or Uncertain, and the Runtime runs it to completion whether or not anyone is left to
hear the answer. Most finish inside the call that started them; one that outruns its call is
Promoted rather than abandoned.
_Avoid_: call, request, tool call, job, task

**Promoted**:
The state of an Operation that outran the time its call had and was allowed to keep running
instead of being killed. Its caller is answered at once with a Handle, so the reply arrives
before the outcome exists. Promotion is never asked for: it is what happens to work that is
too slow, not a mode a caller selects.
_Avoid_: background, async, detached, queued, deferred

**Handle**:
The reference by which a Promoted Operation's result is claimed once the call that started it
has ended. It names an entry in Activity rather than a record of its own, so nothing about it
is a second account of what happened: a Handle whose Runtime has Stopped resolves to the Lost
Operation it points at.
_Avoid_: job id, task id, ticket, token, receipt

**Delivery**:
One arrival of an Operation's call at the Runtime. The transport mints repeats of its own when
a call goes unanswered, byte for byte the same, so one Operation can arrive several times
without anyone asking for it; the Runtime tells them apart by a key the caller puts in the
arguments, does the work on the first, and answers the rest with the first's reply. A second
Delivery is a chance to hand over an answer that was lost, never an instruction to act again.
_Avoid_: retry, attempt, duplicate, redelivery, request

**Uncertain**:
The outcome of an operation whose effects on disk cannot be known — it was cancelled, it
timed out, or its Root went Broken partway. Distinct from a failed operation, which
guarantees nothing changed and is therefore safe to retry, and from a Lost one, which
produced no result at all.
_Avoid_: failed, error, unknown, partial

**Lost**:
The state of an Operation written to Activity that never completed, because the Runtime
died while it was in flight. Distinct from Uncertain: an Uncertain Operation produced a
result that cannot be trusted, a Lost one produced no result to trust or distrust. Reserved
for a Runtime that was taken from the machine rather than one that left deliberately — a Stop
ends running work itself, and knowing that it did is what makes the difference.
_Avoid_: unknown, crashed, orphaned, uncertain

**Undelivered**:
The state of an Operation that completed and whose answer never reached ChatGPT. The
Runtime knows what happened and only the reply was lost, so it is not Uncertain — but
ChatGPT saw a failure and does not know the change was made. It settles if a later Delivery
of the same Operation carries the answer through.
_Avoid_: failed, uncertain, lost, dropped

**Acknowledged**:
The state of an Activity entry whose unresolved outcome — Uncertain, Lost, Undelivered or
Unclaimed — the user has seen. It is recorded as its own appended fact, never as an edit to the
entry it refers to, so the account stays append-only and the moment an unattended command
was noticed is itself part of it. Acknowledgement is one of the two ways an unresolved entry
is settled and so allowed to expire; the other reaches only Undelivered and Unclaimed, whose
answer a later arrival can carry through without anyone having to see it.
_Avoid_: read, seen, dismissed, cleared, resolved

**Unclaimed**:
The state of a Promoted Operation that completed and whose outcome nobody has collected. Its
Handle was delivered and answered, so it is not Undelivered, and its outcome is known, so it
is not Uncertain — the work simply ran unattended and nobody has heard what it did, which is
what Activity exists for. It settles if a later get_result carries the outcome through, and
otherwise only by being Acknowledged: a Workspace lowered below Command closes the collecting
route, leaving a result nobody is able to claim.
_Avoid_: abandoned, ignored, orphaned, undelivered, stale

**Unconfirmed**:
The state of the connector in ChatGPT when nobody has confirmed it was created against the
catalog the Runtime now serves. It is not a measurement — nothing on this machine can see the
connector at all — but the absence of the user's confirmation, which is why only the user can
settle it. A fresh install is Unconfirmed because no connector exists yet, and a version whose
catalog differs returns it to Unconfirmed because the snapshot ChatGPT froze is now the wrong
one. It says nothing about the link: a Connected Runtime can have an Unconfirmed connector, and
that pair is the ordinary state of a machine that has just been upgraded.
_Avoid_: stale, out of date, unlinked, disconnected, Broken (a Workspace whose Root moved),
Failed (the link, not the connector)

**Activity**:
The append-only account of every Operation, written before a mutation begins and completed
after. It is how a Command Workspace can be reviewed after running unattended, and how an
Operation that completed but lost its answer is told apart from one that never ran. A
reporting mechanism, never a recovery one: nothing is ever replayed from it.
_Avoid_: activity log, activity record, audit log, history, journal

**Origin**:
Which surface an Operation arrived from: ChatGPT through the tunnel, or a frontend at the
management seam. It is fixed per surface rather than chosen per call, carries no
conversation identity — the Runtime holds none — and never selects a Workspace, which every
Operation names for itself.
_Avoid_: caller, session, client, user, source
