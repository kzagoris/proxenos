# Proxenos — implementation spec

This is the implementation spec, and it is canonical: it lives here, versioned with the code it
specifies.

It is written to be read on its own. Where the reasoning is load-bearing enough that a future
reader would otherwise undo it, it is an ADR in `docs/adr/`. Domain terms are defined once, in
[`CONTEXT.md`](../CONTEXT.md), and used here exactly as defined — **Workspace**, **Root**,
**Access Level**, **Operation**, **Delivery**, **Promoted**, **Handle**, **Activity**,
**Uncertain**, **Lost**, **Undelivered**, **Unclaimed**, **Acknowledged**, **Origin**.

Every factual claim below about the tunnel, ChatGPT or the toolchain was **measured**, not read off
documentation. Where measurement and documentation disagree, the measurement is recorded and the disagreement is named.

**No decision on the route is open.** The last one — first-run setup and connector re-creation —
is written into §11. Nothing here is a guess.

---

## 1. What is being built

A background **Runtime** on one Linux machine exposes several local directories to ChatGPT
through the OpenAI Secure MCP Tunnel, so a conversation can read and change work here. A local
frontend controls what each directory allows. The first frontend is a lightweight TUI for
configuration and for checking selected functions; a GUI is expected later and its technology is
not chosen.

Single local user, Linux first. Conversations stay in ChatGPT: there is no chat interface, no
model selection and no model hosting here.

Six artifacts:

```
core-api   interfaces, domain types, Operation / ManagementAct / Outcome   ← frontends depend on this
core       java.nio, ProcessBuilder, git, registry, Activity, supervision
mcp        Kotlin MCP SDK + Ktor CIO unix connector                → core
control    wire protocol + client implementing WorkspaceManagement → core-api only
runtime    composition root, owns main() and the tunnel child
tui        Mosaic                                                  → core-api + control
```

`core-api` does not win the deletion test — delete it and you get a fatter frontend dependency,
not reappearing complexity. It earns its place as a guardrail: without the split a frontend can
construct the core directly and bypass the control socket, and a core that dies with its frontend
is the wrong lifetime.

---

## 2. Domain rules

### 2.1 Workspace and Root

A **Workspace** is a named registration of one local directory. Identity is a **generated id**;
the **Root** is an attribute, never the identity. A Workspace therefore survives a rename or a
move of its directory — and a *different* directory later appearing at the old path does not
inherit its access.

- Names are user-chosen, prefilled from the directory name, **unique across all registrations
  including those at None**, and renameable. Resolution is **exact match only** — no case
  folding, no nearest match — so a model that half-remembers a name fails loudly instead of
  hitting the wrong project. A rename leaves an in-flight conversation using the old name, which
  gets a plain "no such workspace".
- **No Root is refused**, including `/` and `$HOME`, and no warning is shown for them. It is the
  user's machine.
- **Overlapping and nested Roots are allowed.** `~/Projects` and `~/Projects/api` may both be
  registered. The **named** Workspace's Access Level applies, with **no union and no
  intersection**. Most-restrictive-wins was rejected because it breaks the promise the dial
  makes; forbidding overlap was rejected because browsing `~/Projects` at Read while one
  repository inside it sits at Command is the main reason to expose a parent at all. The frontend
  warns at registration and names the overlap.
- Being a Git repository is a *discovered attribute* of the Root, not a condition of
  registration.
- **Broken**: the Root no longer resolves to the directory it was registered against. A Broken
  Workspace is absent from discovery and rejects every call until the user re-confirms it in the
  frontend. **Re-confirmation drops the Access Level to Read** whatever it was before, because
  the thing on disk changed identity. Broken-ness is recomputed at each Runtime start and is
  never persisted.

### 2.2 Access Level

One dial per Workspace, an ordered ladder: **None → Read → Write → Command**, each level
including those beneath it. New registrations default to **Read**. **None is the withheld state
itself** — there is no separate visibility toggle to fall out of sync with the level. Revocation
is setting the dial down.

**The level alone authorizes. There is no per-call approval prompt.** The Runtime outlives the
frontend, so a prompt would block against a frontend that is not running, or fail
indistinguishably from a broken tunnel — and the Runtime would stop being useful for exactly the
operations it exists to perform. The cost is stated rather than hidden: a Workspace left at
**Command** can run anything, at any time, with nobody present. That is the argument for Command
being rare and specific, and it is why **Activity** is load-bearing rather than a convenience.

Rejected: per-tool checkboxes per Workspace (a configuration surface nobody audits); a global
command switch plus per-workspace on/off (enabling commands for one scratch directory enables
them everywhere).

**A level change governs calls that arrive after it and does not reach into work already
running.** Lowering `api` from Command to Read while a ten-minute build runs does not stop that
build; the next call is refused. **Revocation is not a stop button**, and no interface may imply
it is.

### 2.3 What a withheld Workspace discloses

A Workspace at **None** is absent from `list_workspaces`, and a call that names it is answered
**`NoSuchWorkspace`** — the same answer an unregistered name gets — with the error naming the
Workspaces that *are* exposed. An error stating its level would hand over in an error exactly
what discovery was careful not to say. **`LevelTooLow`** is reserved for the levels above None,
where the Workspace is discoverable already.
[ADR 0006](adr/0006-a-withheld-workspace-reads-as-absent.md).

A **Broken** Workspace keeps **`WorkspaceBroken`** and is named plainly: it was discoverable
until the disk changed under it, and the frontend needs that reason as its cue to offer
re-confirmation.

### 2.4 Path confinement, and where it stops

Every path argument to the **file, search and Git** tools resolves against the Root, and the
resolved **real** path must sit under the Root's real path: symlinks are followed and *then*
checked, `..` traversal and absolute paths are rejected. The error **names the symlink** that
caused a rejection, so a legitimate one reads as fixable; the remedy for wanting that target is
to register it as its own Workspace.

**`run_command` is bounded by none of this.** A command at Command level runs with the full
authority of the user's Linux account, which is wider than the Root — it can read `~/.ssh`
whatever the confinement rule says. The Root is **routing context, not confinement**. This is
written into the glossary definition of **Command** so it cannot quietly be dropped, and the
frontend must state it in those words when a Workspace is raised to Command. It is the one place
the workspace metaphor stops being true.

Mandatory sandboxing was considered and **rejected by the user**: commands are trusted local
commands under their own account. Do not silently add a sandbox requirement, and do not claim
workspace confinement for arbitrary commands.

---

## 3. Routing and the catalog

**One tunnel, one connector, every exposed Workspace behind it**, routed by a mandatory
`workspace` argument on every scoped call. A tunnel per Workspace buys nothing the argument does
not already give, costs a credential and a ChatGPT-side setup step per registration, and would
break `list_workspaces`, which only reads as one catalog if there is one server. Several clients
on one tunnel ID is competing-consumer with no affinity, which the tunnel's own deployment docs
warn against.

**The cost, stated rather than hidden:** any conversation that reaches the connector reaches
every Workspace above None. There is no per-conversation subset. "This chat sees only `api`"
would be a second tunnel, not a setting.

**There is no ambient current workspace at any layer.** A scoped call arriving without a
`workspace` argument is rejected with an error naming the exposed Workspaces — never defaulted,
**not even when exactly one Workspace is exposed**. A stateful `select_workspace` was rejected
because a stale selection edits the right file in the wrong project and nothing in the transcript
looks wrong.

**The catalog is flat and static.** One set of tools, never tools × Workspaces. Its shape never
changes when an Access Level changes: a dynamic catalog would depend on `tools/list_changed`
reaching ChatGPT, which is not established, so a stale catalog would be silently wrong.
Enforcement happens when a call arrives.

**The core owns the catalog as data** — name, argument schema, required Access Level — and the
`mcp` adapter renders it into `addTool` calls. Flatness, the mandatory argument and the static
shape are domain decisions, not MCP details; in an adapter, a second adapter could silently
violate them. It also lets a frontend show exactly what ChatGPT sees from the same source of
truth, which is half of "checking selected functions".

---

## 4. The eleven tool contracts

Ten were chosen deliberately rather than inherited from another product's catalog; `get_result`
is the eleventh, and it ships now rather than later because **ChatGPT snapshots the catalog with
no refresh** — adding a tool later is not a version bump, it is every user deleting and
re-creating their connector by hand.

| Tool | Level | Key | Notes |
|---|---|---|---|
| `list_workspaces` | — | — | discovery |
| `list_directory` | Read | — | |
| `read_file` | Read | — | bounded |
| `search` | Read | — | filename and content |
| `git_status` | Read | — | |
| `git_diff` | Read | — | |
| `git_log` | Read | — | |
| `write_file` | Write | `request_id` | |
| `edit_file` | Write | `request_id` | exact-match-once |
| `run_command` | Command | `request_id` | may be **Promoted** |
| `get_result` | Command | — | redeems a **Handle** |

Every scoped tool takes `workspace` as a required argument. The three mutating tools additionally
take a required `request_id` (§6.4). The seven read-only entries and `get_result` take no key:
repeating a read costs work and nothing else, and `get_result` is idempotent.

**`list_workspaces`** returns, per Workspace: name, Root, Access Level, and whether the Root is a
Git repository. **Workspaces at None are omitted entirely** (§2.3). Broken Workspaces are
omitted.

**`read_file`** takes line-oriented `offset` and `limit`. The ceiling is **2000 lines** *and* the
byte cap (§6.5), whichever binds first — 2000 is what a call with no `limit` gets and also the
most any `limit` is given, since the point of the ceiling is that paging is deliberate — and the
response states the file's **total line count** so the model knows what it has not seen and can
page deliberately. UTF-8 with malformed
bytes replaced rather than thrown. **Binary files are refused with a plain error** rather than
returning replacement-character soup that wastes the cap and says nothing.

**`search`** enumerates with `git ls-files --cached --others --exclude-standard` inside a
repository — tracked plus untracked-not-ignored, which gets the project's real ignore rules for
free instead of reimplementing `.gitignore` parsing. Outside a repository, a plain walk skipping
`.git/`. A bare walk was rejected because `node_modules`, `build/` and vendored dependencies fill
the cap before reaching the user's own code, and a hardcoded skip list is wrong for somebody's
project by definition. Matching is **literal substring by default with an opt-in `regex` flag**,
case-sensitive with a flag, **binary files skipped** on NUL detection, symlinks leaving the Root
skipped rather than followed. Results are capped with an explicit truncation marker. Search is
**time-bounded rather than promoted**: at the budget it returns what it found with an explicit
*truncated by time* marker. It is read-only and capped by design, so that is an `ok` with less in
it, not an **Uncertain**.

**`edit_file`** is a **byte-exact match that must occur exactly once**. Zero matches → `failed`.
Several matches → `failed` with the count, never first-one-wins. The uniqueness requirement *is*
the concurrency check — if another process changed that region, the match no longer hits — so
there is no mtime or hash precondition token, which would be ambient state carried between calls.
**One edit per call, no batch**, so multi-file partial failure does not exist.

**`write_file`** does **not** create missing parent directories: a hallucinated path fails loudly
rather than growing a tree.

Both mutating file tools land via **temp file in the same directory plus atomic rename**,
preserving permission bits and the file's existing line-ending and trailing-newline convention.
Consequence on the record: rename replaces the inode, so **hardlinks to the file break**.

**`run_command`** takes a single command string run via **`/bin/sh -c`** — the shell semantics a
model actually writes, and a fixed interpreter rather than whatever `$SHELL` happens to be. The
working directory defaults to the Root; an optional `cwd` is path-confined to the Root even
though the command it launches is not. **Environment is inherited from the Runtime process with
the tunnel ID and runtime key explicitly stripped from the child** — without this, any command at
Command level could read the tunnel credentials out of its own environment and print them into a
conversation that leaves the machine.

**Git tools** are read-only and sit at **Read**, so a Workspace can show what changed without
granting command execution. Git mutation is not in the first release; when commands are enabled,
`run_command` reaches it. Where the Root is *inside* a repository rather than being its root, the
tools run at the discovered repository root but are **pathspec-scoped to the Root**, and the
output is **labelled as scoped** so an empty `git_status` is not misread as "the repository is
clean". Where no repository exists above the Root — or `git` is not installed — the tool is
present in the catalog (it is flat) and returns a plain error. `git_diff` defaults to the working
tree against `HEAD`, staged and unstaged together, with an opt-in `staged` flag; `git_log`
defaults to **20 commits** with hash, subject, author and date; `git_status` is parsed from
porcelain output. Every invocation carries **`--no-optional-locks`**, so our read-only tools never
contend with the user's own `git`, and **`GIT_TERMINAL_PROMPT=0`**, so a repository needing
credentials fails instead of hanging on a prompt nobody can see. There is **no snapshot
guarantee**: each tool is one invocation giving a point-in-time view, and a concurrent
`run_command` rewriting the repository can make two outputs disagree. Acceptable for read-only
diagnostics; not worth a repository-wide lock.

**`get_result(workspace, handle)`** returns either **Promoted** again — still running, with the
output captured so far — or the finished Operation with its outcome. **Access is re-checked on
every call**: the named Workspace must still be at Command. Lowering a Workspace to Read stops
the model collecting output from a command already in flight, which is what a user lowering the
dial means by it. A Handle that does not belong to the named Workspace is refused.

---

## 5. Outcomes

Every Operation reaches exactly one of **`ok` / `failed` / `uncertain`**.

`failed` carries a hard guarantee — **nothing on disk changed** — which is exactly what makes a
retry safe. `uncertain` means effects unknown: timed out, stopped, or a Root gone Broken
mid-write. The result text must say ***effects uncertain, do not retry*** in words a model acts
on, rather than relying on a status field being understood, because `failed` and `uncertain` are
the two a model will otherwise conflate.
[ADR 0001](adr/0001-three-valued-operation-outcome.md).

Operation-level problems return as **tool results, not JSON-RPC protocol errors**, and so does a
call the adapter cannot build an Operation from at all — it says plainly that nothing was
attempted. The protocol-error lane is the transport's own, for a malformed frame or an unknown
*method*.

That last sentence originally reserved protocol errors for "a malformed call or an unknown tool",
and the Kotlin SDK at 0.15.0 leaves neither of those ours to give. Measured while building the
adapter: `Server.handleCallTool` catches every exception a tool handler throws and answers it as
a `CallToolResult` with `isError` — only `CancellationException` escapes — and it answers an
**unknown tool name** the same way, before any handler is reached. So the rule the adapter can
actually keep is the one above: it never converts an Operation's outcome into a protocol error.

```kotlin
sealed interface Outcome<out R> {
    data class Ok<R>(val value: R) : Outcome<R>
    data class Failed(val reason: Failure, val message: String) : Outcome<Nothing>
    data class Uncertain(val reason: Uncertainty, val message: String) : Outcome<Nothing>
}
sealed interface Failure      // NoSuchWorkspace, WorkspaceBroken, LevelTooLow, OutsideRoot,
                              // NoMatch, SeveralMatches(count), NotAFile, Binary, NoRepository,
                              // MissingKey, KeyConflict, KeyExpired, AtCapacity…
sealed interface Uncertainty  // TimedOut, Stopped, RootBrokeMidOperation
```

The **text is for the model**; the **sealed reason is for the frontends**, which have to *act* —
a `WorkspaceBroken` failure is the frontend's cue to offer re-confirmation, and parsing that out
of an English sentence would be absurd. The core returns both; the adapter owns only the MCP
envelope. An adapter that paraphrased *effects uncertain, do not retry* into "operation failed"
would reintroduce the exact retry hazard ADR 0001 exists to prevent.

A **Root that breaks mid-operation** is checked once at call admission and **not policed
mid-operation** — the same rule as a level change. If the operation then fails because the
directory vanished, a read is `failed` and a mutation is `uncertain`. Continuous re-validation
would create a second, subtly different notion of Broken for no safety gain.

---

## 6. Execution

### 6.1 The measured transport

Measured against the live tunnel, not inferred:

- **No response deadline is forwarded to the local server.** It exists only as a Go context
  deadline inside the dispatcher. An implementation can never read its remaining budget and must
  not pretend to.
- **The ceiling was measured at 61s** — a 120s call was re-delivered at exactly +61s; a 50s
  control ran once and was delivered. It is **not a constant** (the dispatcher's own drop was
  seen at ~89.7s and 120.0s on another path) and it is **not contractual**.
- **ChatGPT's Stop never arrives** as `notifications/cancelled`. Zero frames in an entire
  session; a call stopped eight seconds in ran its full 300 seconds.
- **Every `tools/call` carries `"id":0`**, so id-based tracking of in-flight work is ambiguous
  under concurrency.
- **The transport delivers a call again when its deadline expires**, byte-identical on this
  transport — same JSON-RPC id, same `_meta`, same arguments, **no marker at all**. One prompt
  was caught executing the same 120s operation **four times**, the last two dispatched *after*
  ChatGPT had told the user the call timed out.
- **MCP Tasks are unreachable.** ChatGPT's client declares the extensions mechanism and lists
  only the UI extension. `execution.taskSupport` is ignored, and a tool declaring it `required`
  is unusable. **Do not design against the Tasks extension.**
- Each call forwards the user's city, region, latitude and longitude.
- The tunnel dispatches at most **10 concurrent MCP requests** with **20** prefetched.
- The Kotlin SDK serializes until `notifications/initialized`, then dispatches concurrently under
  internal caps of **64** executing / **256** in-flight, not tunable from the public API at
  0.15.0.

### 6.2 The budget, and what expiry means

**45 seconds, Runtime-wide, measured from frame arrival. There is no per-call override.** The
override is gone because the model cannot buy time the transport will not give it: a call asking
for `timeout: 300` is a promise the Runtime cannot keep, and asking for more time produces *more
executions*, not a longer one. 45s leaves ~16s under the measured +61s for the response to be
serialized, posted and consumed, and absorbs dispatch latency nobody has measured. It protects
against the **repeat Delivery**, not against the dispatcher. Treat it as a configured constant
the Runtime owns and re-cuts when the transport is re-measured, never as a derived value.

At 45 seconds a `run_command` Operation is **Promoted**: the command keeps running and the call
returns immediately carrying a **Handle**. Promotion is **automatic and never requested** —
there is no `background: true` argument. The model asks for the command; if it is slow it gets a
Handle instead of a corpse.
[ADR 0003](adr/0003-promote-long-running-commands.md).

**Only `run_command` promotes.** The file and Git tools cannot plausibly exceed 45s, and
promoting one would mean a mutation outliving its own answer for no gain. Search is time-bounded
instead (§4).

A **Promoted** reply reports Promoted and carries the Handle. **It is not a fourth outcome and
must not be worded as success**: the outcome set stays `ok` / `failed` / `uncertain`, and every
Operation still reaches exactly one of them. What changed is that a promoted Operation's *reply*
precedes its *outcome*.

The **Handle points into Activity** rather than founding a second store — Activity is already
append-only, already persists, already keeps 30 days and already exempts unresolved entries from
expiry. A Handle whose Runtime has Stopped resolves to the **Lost** Operation it points at.

**Work is never abandoned.** The Runtime always runs an Operation to completion and always
records its outcome, whether or not anyone is left to hear it. Abandoning work mid-flight is
exactly what manufactures **Uncertain**, and `failed`'s "nothing changed" guarantee is bought by
*not* doing that. When an answer is discarded, the Operation finishes, the result is retained,
and the Activity entry is marked **Undelivered** — the Runtime knows what happened and only the
reply was lost. Where there is no promotion, a read that overruns is `failed` and a mutation that
overruns is **Uncertain**.

A promoted command that finishes and that nobody collects is **Unclaimed**. It is not Undelivered
(the reply *was* delivered), not Uncertain (the outcome is known) and not Lost (there is a
result). Nobody has simply heard it.

### 6.3 Stopping, and the one kill on the machine

**There is exactly one kill mechanic.** ChatGPT's Stop is unreachable, so the frontend's stop
control is the only stop button on this machine, and Runtime **Stop** uses the same mechanic:

> Snapshot the descendant tree, then signal **both the snapshotted tree and the process group**,
> **TERM**, wait a **5-second grace**, then **SIGKILL**.

Both arms are required and neither alone is sufficient — measured: a command that calls `setsid`
leaves the targeted group while staying a descendant, and TERM alone never reaps a command that
traps TERM. The tree must be snapshotted **before** the kill, since after reparenting there is
nothing left to walk.

A stopped Operation is **Uncertain**, and `get_result` reports that. **Survivors are never
rounded off to "stopped"**: a reaping that did not finish names the pids still alive and says
they were never signalled, because they forked after the snapshot and had already left the group.

**Runtime Stop**: refuse new calls, then apply the same kill to every running Operation — in
parallel, so Stop costs one grace period in total, not one per command. Work in flight is left
**Uncertain**, not **Lost**: the Runtime chose to end it and knows that it did. **Lost** is
reserved for a Runtime taken from the machine — a crash or an external SIGKILL — where an
Operation was written to Activity and no result exists at all.

`notifications/cancelled` stays handled best-effort and **nothing is designed around it**. It
costs a handler; if one ever arrives it is treated as a stop.

**No maximum lifetime for a promoted command.** The runaway guard is the cap of **4 concurrent
`run_command` executions per Runtime**, which counts promoted commands: a promoted command holds
its slot until it finishes or is stopped. Hitting the cap is **`failed`** — nothing ran, so it is
safe to retry, which is the guarantee doing its job.

### 6.4 Repeat Deliveries

The repeat is byte-identical, so **the only thing that can tell it from a deliberate repeat is a
caller-supplied key**: a required `request_id` on `write_file`, `edit_file` and `run_command`.
The tunnel mints the repeat, so a key the model wrote once is reproduced verbatim by the very
thing doing the repeating.
[ADR 0004](adr/0004-caller-supplied-key-for-repeat-deliveries.md).

A Runtime-derived fingerprint over `(workspace, tool, arguments)` was rejected on the one case
that matters: two deliberate identical `run_command` calls — `make`, twice, on purpose — are the
same bytes as a repeat, and suppressing the second is a silent wrong answer.

**The key is a field on the three mutating `Operation` types and the check lives in the core**,
not in the `mcp` adapter. Every caller supplies one, including a frontend's `TryOperation`, which
runs the same pipeline. The reason is concrete: the key is what intercepts `edit_file`'s
zero-match path, which was otherwise reporting **`failed`** — *nothing changed, safe to retry* —
after the first Delivery had already made the change. That guarantee belongs behind the interface
the tests exercise, not in an adapter a second adapter could diverge from.

| Situation | Answer |
|---|---|
| First Delivery finished | the first reply **verbatim**, with a sentence saying this is the recorded result of an operation already performed |
| First Delivery in flight | **wait on it**, answer on the repeat's own later deadline; `uncertain` if the repeat's own budget expires first |
| Repeat of a **Promoted** command | the **same Handle** — never a second command |
| Same key, different arguments | **`failed`**, naming the collision — literally true, nothing changed on *this* Delivery |
| Missing key | **`failed`**, same path |
| Key reused after expiry | **refused** — *"request_id expired; use a new id only for an intentional new execution"* |

Refusing an in-flight repeat as `failed` was rejected outright: the operation is mutating the
disk at that moment, so the guarantee would be a lie.

Answering a finished repeat is **how an Undelivered entry settles**: the transport repeating a
call *is* a second delivery window for an answer that was lost.

**Retention: 10 minutes past the *reply*** (not past the outcome — a Promoted Operation's reply
can precede its outcome by hours), bounded at ~1024 records, evicted oldest-first. **The bare key
outlives its record**, bounded at 4096, so a key reused after expiry is refused rather than
silently re-run. **Nothing persists across a restart, and nothing needs to**: `tunnel-client` is
the Runtime's child, so a restart kills the transport and no repeat survives to arrive.

**An Access Level lowered between Deliveries does not change this**: serve the stored reply
anyway. A repeat is not a new decision by anyone, nothing new is exposed because the change is
already on disk, and refusing would withhold from the model that the mutation happened. Note the
deliberate asymmetry with `get_result`, which **does** re-check the level: that is a fresh call
the model chose to make.

**`failed` still means nothing changed**, and it survives precisely because a repeat is never
answered with `failed` — not through the zero-match path, which the key now intercepts, and not
through a refusal worded as failure. Restating it as "nothing changed by this Delivery" was
rejected: it would weaken the one sentence that makes a retry safe, in exchange for nothing.

**The tool description is the only lever on a repeat the model initiates itself**, so its wording
is a decision rather than a detail. On each mutating tool, phrased as an instruction the model
acts on:

> Supply a fresh unique `request_id` for each operation you intend to perform. If a call returns
> `uncertain` or times out, repeat it with the **same** `request_id` — this returns the original
> result instead of performing the operation twice. Use a new id only when you intend a separate
> execution.

### 6.5 Output bounds

**64 KiB per Operation**, measured on what is *returned* rather than on what was read — a
malformed byte becomes U+FFFD, three bytes where the input was one, so a cap applied to input
bytes is not a bound on anything the transport carries. Truncation is never silent.

**Command output is cut 32 KiB head, 32 KiB tail**, with an explicit marker naming how many
bytes were dropped. The middle is what a long build repeats; the two ends are where the command
said what it was doing and how it ended.

**A file read is cut at the head only**, and says so. `read_file` pages by line `offset`, so a
hole in the middle would leave the model unable to ask for what it was denied — the marker for a
cut middle is a number of bytes, and the thing a model can act on is a line. A read reports the
file's total line count, the lines it returned, and whether the byte cap rather than the line
ceiling ended it; that is the same promise as the marker, in the terms the next call is made in.
A single line longer than the whole cap is returned cut on a character boundary rather than as
an empty success, because a minified bundle is still a file somebody asked to read.

One number governs four things: what a reply carries, what `get_result` returns, how much a
promoted command's live buffer holds, and how much of a file one read returns. The Runtime's
exposure is therefore bounded at 4 slots × 64 KiB = **256 KiB**. The frontend's one-line preview
and its expanded tail read that same buffer, which is what keeps the screen and the tool
agreeing about what exists.

**No overflow-to-file.** A spill file outside every Root is unreadable by the file tools, so it
would be a path the model is told about and cannot use. Full output lives in **Activity**, where
the unattended-Command account already is.

### 6.6 Concurrency

- **Mutations serialize on a lock keyed by the target's resolved real path** — the same
  resolution confinement already performs, so the allowed nested and overlapping Roots naturally
  contend on the same key. Per-*workspace* serialization was rejected for exactly that reason: it
  does not protect the cross-workspace case the access rules permit.
- **Reads take no lock.**
- **`run_command` takes no lock** — there is nothing meaningful to lock on a command with full
  account authority — but is capped at **4 concurrent executions per Runtime**, counting promoted
  commands, so a model in a retry loop cannot spawn twenty builds and take the machine down. Both
  caps sit comfortably inside the transport's 10 concurrent requests.
- **The core never blocks its caller's dispatcher.** SDK handlers run on `Dispatchers.Default`, a
  CPU-sized pool, and the SDK forbids `Dispatchers.Unconfined` as a workaround — so blocking
  `java.nio` and `ProcessBuilder` work moves to an I/O dispatcher **inside the core**. This is
  part of the interface, not an implementation detail: an adapter that forgot to switch would
  starve the SDK's pool and the failure would look like an unrelated hang. `perform` is safe to
  call concurrently from any dispatcher.
- Promotion needs a **Runtime-scoped `CoroutineScope` with a `SupervisorJob`** that outlives the
  request coroutine which started the work.

---

## 7. Process arrangement and data flow

**The Runtime owns the tunnel.** A long-lived JVM process holds the registrations and the MCP
server, and starts, supervises and stops the official `tunnel-client` executable **as its child**,
which dials back into it over a local Unix domain socket. Rejected: the tunnel as parent spawning
the JVM over stdio — the Runtime's lifetime would become the tunnel's, a tunnel restart would drop
registrations and in-flight work, and Disconnect could not mean anything less than killing the
Runtime. Ownership of the child carries ownership of the credentials: the tunnel ID and runtime
key belong to the Runtime and are passed to the tunnel child and to nothing else.

**Two seams, not one.** The MCP catalog over the tunnel is one. The **management seam** —
register, rename, set a level, re-confirm, forget, connect, disconnect, stop, stop an Operation,
acknowledge, try an Operation — is a **separate local Unix socket** reachable only by this Linux
user, speaking its own protocol. Making the frontend just another MCP client of the same catalog
was rejected: management operations would then live in the catalog ChatGPT enumerates, and raising
an Access Level would become something a conversation could attempt.

```
  ChatGPT ──▶ tunnel service ──▶ tunnel-client (child) ──unix socket──▶ [mcp] ──▶ core
                                        │                                           │
                                  /metrics over                                     │
                                  unix socket ◀──────────────── supervision ────────┤
                                                                                    │
  TUI / future GUI ──▶ [control] client ──unix socket──▶ [control] server ──────────▶┘
```

**Proven by execution, not by documentation** (this whole arrangement rested on an untested
assumption until it was run):

- A Kotlin MCP server binds a Unix socket and speaks MCP over it. The endpoint's default path is
  `/mcp`; `/`, `/api/mcp`, `/message` and `/sse` return 404. Ktor logs `Responding at
  unix://0.0.0.0:80`, which is cosmetic and not a TCP bind.
- `tunnel-client` v0.0.14 reaches it across the socket, with the logical host `.invalid` and so
  unresolvable — an HTTP response from a host that cannot be resolved is what makes this proof
  rather than inference.
- **Post-handshake concurrency is real**: three 3000 ms calls returned in **3086 ms** wall clock
  with `peak_in_flight=3`.
- A JVM supervises the child, reports its exit code, stays alive when it dies, and its shutdown
  hook destroys the child tree leaving **no orphans**.
- **Restart over a stale socket file works**: Ktor unlinks and rebinds, so the parent does not
  need to remove the file.
- The loopback-TCP fallback is **not** needed.

**Three configuration facts the implementation must carry deliberately, because each fails
obscurely:**

1. **`unixConnector` is engine configuration, not an application module.** It is
   `embeddedServer(CIO, configure = { unixConnector(path) }) { … }`.
2. **The logical URL must be `http://`, not `https://`.** `tunnel-client` terminates no TLS on
   the way to a Unix socket, so an `https://` logical URL makes it attempt TLS against a
   plaintext server and the handshake dies with `http: server gave HTTP response to HTTPS
   client`.
3. **The SDK's DNS-rebinding guard rejects the tunnel's logical hostname** unless it is passed as
   `mcpStreamableHttp(allowedHosts = …)`. The failure is a bare
   `403 {"code":-32000,"message":"Invalid Host: …"}` with no hint about the allowlist. **This
   couples the Runtime's server configuration to the tunnel's configured URL** — see §11.

Also measured: `tunnel-client doctor`'s `mcp_server_reachable` check **dials TCP** and fails on
DNS for a socket target, so it is misleading here. `run` honours the override; `doctor` does not.

---

## 8. Runtime lifetime and state

### 8.1 Three distinct acts

- **Disconnect** takes the transport down. The Runtime stays up, registrations are untouched,
  calls fail until it is Connected again. It changes no Access Level and **stops no running
  work**.
- **Revoke** is the Access Level at None, per-Workspace. It is not a new mechanism and it does
  not stop a command already running.
- **Stop** ends the Runtime itself (§6.3).

The test they must pass: *"I'm stepping away, cut ChatGPT off"* is Disconnect; *"this project is
off-limits"* is Revoke. They are never the same button.

### 8.2 Starting, persistence, single instance

The frontend starts the Runtime if it is not running, and there are explicit start and stop
commands. **No autostart at login by default**: that would boot the machine into a state where a
Command Workspace is reachable with nobody present and no human act in between. The cost is
accepted and stated — after a reboot, ChatGPT calls fail until the user starts it once. **The
frontend does not offer to install a unit either** (§11.4): an offer inside the product is the
product recommending the thing it rejected, and it would arrive before the user owns a single
Workspace at Command. `docs/` carries a ready systemd `--user` unit for a user who reads the
reason and disagrees.

**Persisted:** registrations (id, name, Root), **Access Level exactly as set, including Command**,
connect-*intent*, **Activity**, and the **catalog fingerprint the user last acknowledged** (§11.5)
— the one persisted fact in this design that is about something outside this machine. A fresh Start comes up Connected unless the user last
Disconnected deliberately. Dropping Command to Read on every restart was rejected: it sounds safe
and instead trains the user to re-raise levels as routine, which is what makes a dial meaningless.
This deliberately differs from the Broken rule, and should — Broken means the thing on disk
changed identity, a restart means nothing changed at all.

**Ephemeral:** Connected state, Broken-ness, Delivery records, everything in flight.

**Single-instance:** a lock on the socket path; a second Runtime refuses to start and says one is
already running. **Any number of frontends may attach at once** and all see the same state with
live change notifications — a forgotten TUI on another tty must not lock the user out, and a GUI
and a TUI open together must not be a conflict.

**The Runtime holds no per-conversation and no per-MCP-session state whatsoever.** Every call is
answered from its own arguments plus the registry: no remembered directory, no carried-over
handles, nothing making call N+1 depend on call N. Routing reduces to a pure lookup —
`workspace` argument → registration → Root plus Access Level. This is the strong form of the ban
on an ambient current workspace, and it makes the Runtime structurally immune to the tunnel's
no-affinity warning.

### 8.3 We never replay

The tunnel offers **no lease, no heartbeat and no redelivery** we can rely on. A restart is
**not** an execution boundary. An append-only **Activity** entry is written *before* a mutation
begins and completed after, so a restart can honestly show "in flight when the Runtime died,
outcome unknown" — **Lost** — instead of silence. That record is a **reporting mechanism, never a
recovery one**: we do not attempt exactly-once, we never replay and never auto-retry from it, and
ambiguity is made visible rather than resolved by guessing.

### 8.4 The signal that backs Connected

The Runtime reads **one number** — `commands_poll_last_successful_timestamp_seconds` from the
tunnel client's `/metrics`, bound to a Unix socket — and derives every state from it plus its own
start time. [ADR 0005](adr/0005-one-measured-signal-backs-connected.md).

| State | Rule |
|---|---|
| **Connecting** | no successful poll since this Start, or since the user's last Connect (gauge absent, zero, or older than that) |
| **Connected** | last success newer than one poll cycle plus margin |
| **Failed** | there *was* a success, and it has gone stale |
| **Disconnected** | the user's intent — never measured |

**Connecting is one-way.** A Runtime that has been Connected never returns to it: never having
had a link is a different fact from losing one that worked. The one way back is the user's own:
Connect after a Disconnect reads Connecting until a poll succeeds, because the link was taken down
rather than lost, and a success from before it was taken down says nothing about the new one.
Failed would call the user's own act a fault, and Connected would claim what nothing has measured.

Read every **~15s**. The staleness threshold is **derived from the child's own configuration** —
`control-plane.poll-timeout` + `poll-deadline-guardrail` + margin — not fixed. The poll is a
*long* poll (30s wait + 5s guardrail by default), so on a healthy idle tunnel the gauge is
routinely ~35s stale and a hardcoded 60s would report a permanently lost tunnel the moment anyone
raised the wait. The Runtime launches the child, so it knows both numbers. The health listener is
bound with `--health.unix-socket` and the child writes its base URL to `--health.url-file`, so
the rendezvous is not guessed. Reading it needs a **Ktor client over a Unix socket**; the parse is
"find the line beginning with the metric name, take the second field" — no Prometheus parser.

**Both other candidate signals are unusable, confirmed by source.** `/healthz` is a static handler
that writes `200 live` and checks nothing. `/readyz` walks local wiring and **never references
the poller** — it reported `200 ready` while every poll was failing 401 on a fake key.

**`tunnel-client` never gives up and never exits.** Its poller backs off 200ms→10s with jitter and
has no failure threshold, no fatal path and no exit: a 401 on a bad key loops roughly every ten
seconds forever with the process alive. So supervision restarts a child that **dies**, not a lost
link, and "gave up" describes no observable event. **Failed is the Runtime's judgement, not an
action** — we cannot make the poller stop, only stop believing it. If the user fixes a permission
server-side, the gauge goes fresh on its own and the state returns to Connected with no keypress.

**Failed carries a quotation, not a diagnosis.** A tunnel deleted server-side is not
distinguishable from a wrong or under-privileged key — both most likely land on 401
`tunnel_use_forbidden` — so the tunnel's own `mitigation` string is passed through verbatim with
the status code. A log-driven classifier was rejected as a *state* input: those are undocumented
internal strings in someone else's Go source, and a reword upstream would stop the status bar
working with nothing failing loudly. Logs supply only the words.

**`observe()` carries the state, the instant it was entered, and the reason where there is one**,
and emits **one event per transition and nothing else**. A failed attempt that schedules another
is not a transition — the stream stays silent through the whole retry window. A frontend wanting a
spinner has the entered-at instant and a clock of its own.

**What Connected does not say.** It is the strongest claim this machine can measure: the link is
up. A connector deleted in the ChatGPT UI leaves the link up and the Runtime **Connected**, and
nothing here can see it. That gap is stated in the interface rather than smuggled into the word
(§10).

---

## 9. The core interface

```kotlin
interface WorkspaceOperations {                                 // what ChatGPT's calls reduce to
    val catalog: List<OperationSpec>                            // the eleven, as data
    suspend fun <R> perform(op: Operation<R>): Outcome<R>
}

interface WorkspaceManagement {                                 // what a frontend does
    suspend fun <R> perform(act: ManagementAct<R>): R           // Register, Rename, SetLevel, Reconfirm,
    fun observe(): Flow<RuntimeEvent>                           // Forget, Connect, Disconnect, Stop,
}                                                               // StopOperation, Acknowledge,
                                                                // AcknowledgeConnector (§11.5), TryOperation,
                                                                // ReadOutput (§10.2)
```

**Two types, not one**, so that "could a conversation raise an Access Level?" is answered by the
compiler rather than by a runtime check.

**Request-shaped, not method-shaped.** The pipeline every Operation must pass through —

> resolve **Workspace** → check **Access Level** → confine path → check the **Delivery** key →
> take the real-path lock → write **Activity** → shape the **Outcome**

— gets **one home** instead of eleven call sites that can each forget a step. The remote
management client becomes serialize-send-deserialize rather than N hand-written methods drifting
from the core. The generic `Operation<R>` keeps per-operation result typing, so this costs nothing
in type safety: it is not a stringly-typed `execute(String, Map)`.

**The one real seam** is a **management client library** implementing `WorkspaceManagement` over
the control socket, so the TUI now and a GUI later program against the same type the core
implements in-process. By the two-adapter rule this is the only genuine seam in the design, and it
is what "a reusable library core with multiple frontends" actually cashes out to. The frontend
gets the interface, never the implementation.

**Nothing is abstracted below the core.** No `FileSystem`, no `Clock`, no `ProcessLauncher`, no
`Store`, no `TunnelClient`. Real temp directories, real `git`, real child processes; the command
budget and the tunnel executable path are configuration, so tests set a 200 ms budget and point at
a stub script. [ADR 0002](adr/0002-no-abstraction-below-the-core.md). Shape C — ports and adapters
over Registry, AccessPolicy, PathResolver, FileOps, CommandRunner, GitOps, SearchOps, ActivityLog,
TunnelSupervisor — was rejected under the one-adapter rule and the deletion test: most of those
ports vanish, having one adapter each, and the composition they exist to allow goes untested.

**Origin is bound per surface, not passed per call**: `operationsFor(origin: Origin):
WorkspaceOperations`, stamped once at startup wiring — one view for the `mcp` adapter, one for the
control socket. `perform` takes no origin argument. Passing it per call was rejected because any
caller could then claim an Origin that is not theirs, and a bug in the MCP adapter could write
"Frontend" into the Activity record for a ChatGPT command — the one record relied on after an
unattended `run_command`. **Origin binds no Workspace and no conversation.**

**`TryOperation` is a ManagementAct**: a frontend may invoke an Operation, through the same
pipeline with the Access Level enforced identically, because the point of checking a function is
to see what ChatGPT gets — a bypass would show a green tick against a Workspace sitting at None.
It is recorded in Activity with `Origin.Frontend`, and it supplies a `request_id` like any other
caller (§6.4).

**State reaches a frontend as a snapshot, then changes.** `observe()` emits a complete `Snapshot`
first — every Workspace with name, Root, Access Level and Broken-ness, the Runtime state with the
instant it was entered, what is running, the Activity account with the Runtime start it belongs
to, and the catalog as data (§3) — then `Change` events, **on one ordered stream**. Activity
rides the same stream rather than a fetch of its own for the same reason the rest does: Activity
is derived from the same snapshot (§10.2), and a feed fetched beside the stream is a feed that can miss an entry.
Events-only was rejected because a frontend attaching at 3pm would never learn the level set at
10am. Two calls — fetch state, then subscribe — were rejected because a change landing in the gap
is lost and the screen is silently wrong until the next unrelated event.

**Configuration is taken, not sourced.** The core takes a `RuntimeConfig` at construction and
nothing more (§11).

---

## 10. Activity and the frontend

### 10.1 Activity

Append-only, written **before** a mutation begins and completed after. Each entry carries time,
**Origin**, Workspace, tool, the arguments in summary, elapsed time, the outcome, and which
Runtime start it belongs to.

**Retention is bounded by age, not count: 30 days — and an unresolved outcome never expires until
it is settled.** The unresolved states are **Uncertain**, **Lost**, **Undelivered** and
**Unclaimed**; they are what the record exists for, they are rare, and they are flagged in the
gutter and counted rather than left to scrolling. Count-bounded retention was rejected: 200
entries is one unattended afternoon.

**Refused calls are recorded** as ordinary `failed` entries — a model repeatedly naming a
Workspace you withheld is exactly what the unattended account exists to show, and omitting it
would make the feed lie by omission. They are **not** flagged in the gutter, because nothing about
them is unresolved, so the `!` count stays meaningful and 30-day retention absorbs the volume.

**Acknowledgement is an appended fact, not a flag.** A flag would be a mutation of a written
record, the one thing Activity forbids. Acknowledged state is folded out of appended `Ack`
records, so *when* an unattended command was noticed is itself part of the account. It is a
**ManagementAct**.

**An Operation is recorded once; each subsequent Delivery appends a fact against it.** When a
repeat successfully hands over a stored reply, that append is what **settles an Undelivered**
entry. A Delivery per entry was rejected — the user would see four entries for one thing they
asked for once, which is exactly the confusion the repeat causes. Hiding repeats was rejected
because it conceals the transport's behaviour from the one surface built to reveal what happened
unattended.

**Unclaimed** settles the same two ways **Undelivered** does: a later `get_result` carries the
outcome through with nobody having to look, or acknowledgement otherwise. The second route is
load-bearing — lowering a Workspace below Command refuses `get_result` from then on, so the
collecting route closes and a human has to look.

### 10.2 The TUI

**Workspaces is the home screen.** A vertical list shows each name, Root, Access Level,
and a short actionable problem message. Up/down selects a Workspace. `[n]` adds one;
`[m]` or Enter opens Manage, where access changes, rename, re-confirmation, and Tools live.
Long explanations and confirmations replace the current screen until dismissed.

The home screen carries one compact status line — **Runtime**, **Tunnel**, **Connector** — each
link measured on its own, in the order a call travels. `[i]` opens **Review**: one row per stage
carrying its state, with that stage's detail and actions behind `[Enter]`. A stage that cannot be
measured yet — the tunnel and the connector while the Runtime is not running — reads **Can't
tell**, never a fault, because nothing has been measured. `[S]` starts a Runtime that is not
running — from its detail, or from any screen while the status line reads **Not running** — and
`[X]` stops a running one from the Runtime detail; `[d]` connects or disconnects from the Tunnel
detail; `[C]` records the connector acknowledgement from the Connector detail. A connection problem appears as one
short Review action on Workspaces; connector confirmation does not banner there — it is the
Connector row's own state. The detailed Unconfirmed instructions (§11.5) and first-run credential
panel (§11.3) live in those stage details. Their triggering conditions and acknowledgement
semantics are unchanged. The stage split and the unmeasurable reading are
[ADR 0009](adr/0009-each-stage-is-measured-on-its-own.md).

`[a]` opens **Activity**, which shows only entries matching the current Runtime start. Previous
runs have no history option, rows, selection targets, or attention indicators in this frontend.
The Runtime retains its full records under its existing retention rules; this is a display
filter. Reopening the dashboard retains the current run's Activity. A Runtime restart resets
the displayed Activity. There is no frontend identity or last-seen marker.

A compact Activity indicator on Workspaces reports running commands and current-run outcomes
needing attention. The running-work band lives on Activity and is absent when nothing runs.
If access is lowered while a command runs, Workspaces and Manage show a short warning; selecting
the command in Activity shows the full explanation and stop action from §10.3.

`[Esc]` returns to Workspaces (or closes a detail/confirmation first). `[q]` is labelled
**Close dashboard** and leaves the Runtime and exposure unchanged. The interactive dashboard
uses the terminal's alternate screen so launch/debugger output does not share the UI.
Details can be scrolled with Page Up/Down; workspace and tool selection stays visible.

Running work does **not** live as the feed entry updating in place — that was built and dropped,
and not for the expected reason: **a promoted command manufactures the entries that bury it**,
since every `get_result` about it is an Operation of its own written after it. The feed is where
that Operation's record belongs and the worst place to watch it from.

**Consecutive `get_result` polls of one Handle draw as one counted row** (`get_result ×7`) — a
*display* fold, never an edit. Every poll stays in the record.

**One line collapsed, the whole tail expanded.** Every band row carries the last captured line;
the selected row expands in place to the buffer `get_result` would return, labelled so the screen
and the tool agree about what exists.
The frontend reads that buffer with the `ReadOutput` act, which takes it exactly as `get_result`
does, rather than having it pushed on the stream: output is not state, and a frontend nobody is
looking at then costs the Runtime nothing. The stream carries what the band's shape depends on —
that a command started, was Promoted, reached a phase of its stop, and ended.

**Stopping is `s` then `y`**, any other key cancels. It confirms rather than firing on one key,
because what is being ended is work whose disk effects become unknowable the moment it dies. It
stays a keypress — typed confirmation was rejected, since keys rather than a command line are the
interaction model. The confirmation names the command, the Workspace, the start time and the
elapsed, and says in those words that the command ran with the full authority of the Linux
account, that the result is **Uncertain**, and that nothing is rolled back.

**A Stopping Operation stays in the band until it is reaped** — it is still running, which is the
entire point of TERM-then-grace-then-KILL, and a band that emptied on the keypress would call the
machine quiet while a process tree was alive on it. Its row shows the phase and says *already
stopping — there is no second, harder stop*.

**The per-Workspace detail pane is behind one key**, carrying the eleven-tool catalog rendered
against that Workspace's current level, each entry marked permitted or not and annotated with
*why*. This is the only place the `run_command` authority wording survives after the
raise-to-Command banner is dismissed, and it is "checking selected functions" made concrete — the
catalog and `TryOperation` sit in the same view.

Activity shows the current Runtime start time. There is no previous-run divider.

### 10.3 Wording the interface is obliged to carry

Each is a place the screen could quietly lie:

- Raising to **Command** states that commands run with the full authority of the user's Linux
  account and are **not bounded by the Root**, naming `~/.ssh` concretely, and that there is no
  per-call prompt, so the level authorises while nobody is watching.
- A **Root that overlaps** an existing one warns and names the overlap, and says there is no union
  and no intersection.
- **Lowering a level does not stop running work.** The selected command detail names the running command and its
  start time and points at stop. Where a promoted command is running, it must say **both** that
  revocation did not stop it **and** that its result can no longer be collected — say one without
  the other and it implies the wrong thing.
- **Re-confirming a Broken Workspace** says it was at Write, that it lands at Read, and why: the
  thing on disk changed identity.
- **Undelivered** says ChatGPT saw a failure and does not know the change was made, and that
  nothing is replayed or retried.
- **Failed** names the reason, quotes the tunnel's own complaint, and cannot be mistaken for
  **Disconnected**: one is a problem, the other is the user's decision.
- The **Unconfirmed** detail in the Connector stage of Review carries the literal steps — delete the app in ChatGPT, New Plugin,
  select the tunnel, **choose No Auth and not the OAuth default** — and says plainly that nothing
  on this machine can check whether they were done, so acknowledging records the user's word and
  not a measurement.
- The **first-run credential panel** quotes the tunnel's `status_code` and `mitigation` verbatim
  and never diagnoses, because a deleted tunnel and a rejected key are the same 401. It names the
  three things only the user can check, and it does not say the key is wrong.
- The **Runtime detail** states that there is no autostart, that this is deliberate, and that
  calls will fail after a reboot until the Runtime is started once — so the silence is expected
  rather than a fault to hunt.
- In the **Tunnel detail** behind a key:

  > Connected means the tunnel between this machine and OpenAI is up. It does not mean ChatGPT
  > still has a connector pointed at it: deleting the connector in ChatGPT leaves this reading
  > unchanged, and its catalog is a snapshot that never refreshes.

---

## 11. Configuration and first run

The core **takes** a `RuntimeConfig` and deliberately does not source it. The `runtime` app
sources it, and the rule is that **the user supplies exactly one thing**:

| Field | Where it comes from |
|---|---|
| tunnel ID, runtime key | **the credentials file — the only mandatory artifact** |
| state directory | `$XDG_STATE_HOME/proxenos`, else `~/.local/state/proxenos` |
| MCP socket path | `$XDG_RUNTIME_DIR/proxenos/mcp.sock` |
| management socket path | `$XDG_RUNTIME_DIR/proxenos/control.sock` |
| tunnel health socket path, health URL file | derived from the MCP socket path |
| tunnel executable path | `tunnel-client` on `PATH`, else the state directory's `tools/` — which is where the shipped `install-tunnel-client` puts it ([`INSTALL.md`](INSTALL.md)) |
| logical host | **a compiled-in constant. Not a field. See below.** |
| budget / grace | 45s / 5s |
| tunnel poll wait / guardrail | 30s / 5s — `tunnel-client`'s own defaults, passed to the child explicitly because the staleness threshold behind Connected is derived from them (§8.4) |
| output cap | 64 KiB (32 head, 32 tail) — **fixed by the build, not a tunable**: `read_file`'s catalog entry states it, so changing it changes the catalog ChatGPT froze (§11.5) |
| `run_command` concurrency cap | 4 |
| Delivery retention / record quota / key quota | 10 min / ~1024 / 4096 |
| Activity retention | 30 days |

An optional `config.toml` beside the credentials file overrides any path or tunable. It **never
carries the credential**, so it stays a file the user can diff, copy between machines and paste
into a bug report. Environment variables override paths only, never the credential — a key in a
shell profile or a unit file is a key in a backup.

### 11.1 The credentials file

`$XDG_CONFIG_HOME/proxenos/credentials`, mode `0600`, **refused if the mode is wider**, two
keys: the tunnel ID and the runtime key. The Runtime reads it into memory and passes both into
the tunnel child's environment as `CONTROL_PLANE_TUNNEL_ID` and `CONTROL_PLANE_API_KEY`, which is
how `tunnel-client run` takes them. It does **not** put them in its own environment. §6's rule
that `run_command` strips both from every child environment therefore stops being the only guard
and becomes defence in depth, which is what it should have been.

If the file is absent, `runtime` **refuses to start** and names the file and the setup wizard.
It does not prompt: the Runtime has no terminal when a frontend starts it, and a background host
that blocks on stdin is a background host that hangs.

### 11.2 The logical host is ours and is never seen

The logical URL is not registered with OpenAI. It is a local flag —
`--mcp.server-url url=http://<host>/mcp,unix-socket=<path>` — that the Runtime itself passes to
the child it launched. So the Runtime owns **both** ends of the coupling §7 found, and one
compiled-in constant seeds them:

```
logical host  = proxenos.internal   (a constant)
child flag    = http://proxenos.internal/mcp
allowedHosts  = [ proxenos.internal ]
```

The user never sees it, and it is not a configuration field. This is not a convenience: two
fields that can disagree is exactly how the bare `403 {"code":-32000,"message":"Invalid Host: …"}`
gets built, and a user-editable `https://` is how `http: server gave HTTP response to HTTPS
client` gets built. Neither failure explains itself. A `config.toml` override may replace the
constant, and still derives both sides from that one value.

### 11.3 A wrong key on the first run

**Connecting** stays what §8.4 made it: truthful, one-way, permanent, escalating to nothing. A
bad key is a 401 looping roughly every ten seconds forever, and the Runtime does not invent a
state for it.

What the first run adds is **words, by the mechanism ADR 0005 already accepted** — logs supply
reason text, never a state input. After one long-poll wait with no success since Start, the
**Tunnel detail** carries a panel with the tunnel's own `status_code` and `mitigation`
**verbatim**, and adds the three things only the user can check:

- the tunnel ID matches the tunnel they created,
- the runtime key has not been revoked,
- the key belongs to **that** tunnel.

It must not diagnose. A deleted tunnel and a rejected key both land on 401 `tunnel_use_forbidden`,
and ADR 0005 settled that we quote rather than guess.

**One free upgrade, probed not assumed:** once per Start the Runtime requests
`/health/control-plane` once — at the moment the panel falls due rather than the instant of Start,
because a client asked before its first poll has failed nothing and its category would say
nothing. On `tunnel-client` `main` it answers with `consecutive_failures`, `failure_category` and
`next_retry` — this question in machine-readable form — and the panel uses `failure_category`.
v0.0.14 404s the route, which is a clean feature probe, and the panel falls back to the
quotation. Nothing else changes: it is still not a state input.

### 11.4 No autostart unit, and no offer of one

§8.2 rejected autostart at login because it boots the machine into a state where a Command
Workspace is reachable with nobody present. **The TUI therefore never offers to install a unit.**
An offer inside the product is the product recommending the thing the product rejected, and it
would arrive at first run — before the user owns a single Workspace at Command, and so before
they can weigh what they are consenting to.

Instead the Runtime detail pane states the rule and its cost in one sentence, and `docs/` carries
a ready systemd `--user` unit, [`docs/systemd/proxenos.service`](systemd/proxenos.service),
for a user who reads the reason and disagrees. **The cost is
stated rather than hidden:** after a reboot the local failure is silent, and the user meets it as
tool errors inside ChatGPT, which is the worst place to debug it.

### 11.5 The connector, and why first run and re-creation are one surface

Two measured operator facts: ChatGPT **snapshots the catalog with no refresh**, and **Disconnect
in the ChatGPT UI deletes the plugin definition outright**. The tunnel object survives and is
re-selectable, but re-creation must choose **No Auth** — the dialog defaults to OAuth and that
fails before any tool call. Nothing on this machine can detect that the connector is gone.

The Runtime hashes the catalog it owns as data (§3) into a **catalog fingerprint**, and persists
the fingerprint the user last confirmed their connector was built against. Absent or different
means the connector is **Unconfirmed**: the Connector row in Review reads it, and its detail
carries the literal steps and says plainly that nothing here can check whether they were done,
until the user acknowledges it. Acknowledging stores the current fingerprint.
[ADR 0007](adr/0007-a-catalog-fingerprint-the-user-acknowledges.md).

Because the catalog is **flat and static**, `Unconfirmed` cannot be reached by registering a
Workspace or moving an Access Level. It is reached on a fresh install, where there is no stored
fingerprint, and by installing a version of this product whose catalog differs. Those are the
same dialog in the same browser, so they are one code path and one setup detail.

### 11.6 What the wizard does, and what it does not

The half of first run that is **not** the connector — create a tunnel in the Platform
organization, create a runtime key, associate the tunnel with the intended ChatGPT workspace, and
enable developer mode, which lives under **Settings → Security and login** rather than anywhere
one would look for it — happens in a different product's dashboard, before anything on this
machine can run. A shipped `wizard` script walks it and writes the credentials file at `0600`
with a hidden read, never echoing and never committing.

**This corrects a premise.** Onboarding was charted as one surface on the grounds that first run
and re-creation are both "the user standing in the ChatGPT UI with a dialog". Half of it is: the
connector half. The other half is the Platform dashboard, it happens exactly once, and it has to
complete before the Runtime will start at all. So the split is by **what recurs**, not by what is
new:

| | Where | When | Who says it |
|---|---|---|---|
| tunnel, key, developer mode | Platform dashboard | once, before anything runs | the wizard script |
| the connector | ChatGPT UI | first run **and** every catalog change | Review in the TUI |

The TUI never collects credentials. It has no place to: it is a frontend that attaches to a
running Runtime, and the Runtime will not run without the file. Rotating a key is re-running the
wizard or editing one `0600` file.

---

## 12. Dependencies

| | Version | Note |
|---|---|---|
| Kotlin | **2.4.20** | drives Mosaic 0.18.0 fine |
| JDK | **26** toolchain | via `mise`, which supplies the launcher too; Gradle provisions one where the machine has none |
| Gradle | **9.7.1** | |
| Kotlin MCP SDK | **0.15.0** | `kotlin-sdk-server` ships **no** engine |
| Ktor | **3.5.1**, **CIO** | **tracks** what the SDK resolves ([ADR 0008](adr/0008-track-the-sdks-ktor.md)); CIO is the only engine with Unix socket support (3.2.0+), both directions |
| Mosaic | **0.18.0** | publishes **no** Gradle plugin after 0.12.0 |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` at the Kotlin version | |
| `tunnel-client` | **v0.0.14** linux-amd64 | official executable, **not** committed; the distribution's `install-tunnel-client` verifies it against `SHA256SUMS.txt` and installs it in the state directory's `tools/` |
| `git` | host binary | required **only** by the three Git tools; its absence takes the same plain error path as "no repository" |

**The toolchain is provisioned, not assumed.** Gradle has shipped no JDK downloader of its own
since 7.6; it resolves a missing toolchain through a resolver plugin, and `settings.gradle.kts`
applies the Gradle team's foojay resolver at **1.0.0**. That version is the one version string
outside `gradle/libs.versions.toml`, and it has to be: Gradle evaluates a settings `plugins` block
before it reads the catalog.

Gradle's installation suppliers are asdf, SDKMAN, jabba, IntelliJ, `/usr/lib/jvm` and Maven
toolchains — **there is no supplier for `mise`**, so a JDK that only mise has is invisible and gets
downloaded again. mise's layout is asdf's, so `ASDF_DATA_DIR=~/.local/share/mise` makes the
installed one visible instead. Measured while the pin was briefly 21, which is the only way to
exercise provisioning on a machine whose launcher is 26: with `~/.gradle/jdks` emptied, Gradle
fetched Temurin 21.0.12.1 and compiled with it in about two minutes, from the vendor CDN rather
than from `services.gradle.org`.

**26 is not an LTS release** — the line is 8, 11, 17, 21, 25 — so this pin carries a six-month
horizon and is expected to move rather than sit still. It is chosen because the machine's JDK is
26 and one JDK is simpler than two, not because 26 is the safe end of the range. The whole point
of declaring a toolchain at all is that this choice stays a single line: nothing downstream reads
the JVM that launched Gradle.

**`google()` is required in `repositories`** for the `tui` artifact: Compose pulls
`androidx.lifecycle:lifecycle-runtime` and `androidx.annotation:annotation`, which are absent from
Maven Central.

**Search is implemented in-JVM**, not delegated to ripgrep, so there is one code path and no
assumption about what the host has installed. **PowerShell is unused** — not a required runtime,
not a bundled backend, not the command shell. Node- and Python-based MCP servers were rejected on
one ground: each adds a second runtime to supervise and arrives with its own path-handling and
error semantics that would have to be re-wrapped to satisfy the rules above, the wrapper being
most of the work either way.

**Ktor tracks the SDK and does not float ahead of it** — settled in
[ADR 0008](adr/0008-track-the-sdks-ktor.md). The catalog's Ktor line is what `kotlin-sdk-server`
resolves and moves in the same commit as the SDK line; newer Ktor releases are not taken, except a
security fix in a module the Runtime loads, and then only to the patch that carries it.

**The distribution** is one tree, built by the root project's `distribution` plugin
(`./gradlew distTar`): `bin/runtime` and `bin/tui` over one shared `lib/`, because the TUI starts
the Runtime it finds beside its own launcher; `bin/wizard`; `bin/install-tunnel-client`, into which
the build writes the catalog's `tunnel-client` version; and `docs/INSTALL.md` with the systemd
unit. It needs a Java 26 runtime on the machine — the code is compiled for the toolchain — and it
carries none. [`INSTALL.md`](INSTALL.md) is the path from a clean machine to the first call.

**Linux-first stands and no portability abstraction is built now.** One adapter is a hypothetical
seam; Windows would be the second, and it is designed then. What a future Windows port must
reopen, so it is not rediscovered: the tunnel → Runtime hop becomes loopback TCP **plus an auth
story** (`tunnel-client` silently downgrades its other UDS features to TCP on Windows and has no
named-pipe target at all); the management seam **loses peer-credential authentication** (Windows
AF_UNIX carries no ancillary data, so local protection would be filesystem ACLs only, and no
named-pipe path exists for Ktor); and the **Command** glossary entry, written in terms of "the
user's Linux account", stops being true as written.

---

## 13. Validation

### 13.1 Test surface

**The interface is the test surface.** The eleven Operations are exercised through
`WorkspaceOperations` with **no transport** — that is the whole reason shape A was chosen over a
sealed Runtime whose operations could only be reached through Ktor, a Unix socket and an MCP
client. Nothing below the core is abstracted, so tests use real temp directories, a real `git`, and
real child processes; the budget and the tunnel executable path are configuration, so a test sets a
200 ms budget and points at a stub script.

Two things need their own harness rather than the operations interface: the **Connected state
machine**, driven by a fake `/metrics` responder on a Unix socket with a controllable gauge value,
and the **control protocol**, driven by the management client against an in-process core so that
the client and the core are proven interchangeable — the design's one real seam, and the only
place two adapters actually exist.

Mosaic **renders without a TTY**, emitting frames as plain lines, which is what makes both a
`--dump` mode and a pty-driven harness possible for the `tui` artifact. One prototype lesson
carries into it: **a cursor's target list must be the display order** — a variant that walked the
feed newest-first while drawing it oldest-first moved its arrow keys the wrong way, and a pty
harness that presses keys without judging direction will not catch it.

### 13.2 Acceptance scenarios

Fourteen. Each is observable, and each fails loudly if the decision behind it was quietly dropped.

| # | Scenario | What it proves |
|---|---|---|
| 1 | Two Workspaces at different levels, calls interleaved; one call omits `workspace`; one names a Workspace at None | routing by the mandatory argument, **no ambient default even with one Workspace**, and a withheld Workspace answering `NoSuchWorkspace` (§2.3) |
| 2 | Lower a level from Command to Read while a command runs | the change governs later calls and **stops nothing**; the next call is refused |
| 3 | Two `edit_file` calls on one file, arriving through two overlapping Roots | the mutation lock keyed on the **resolved real path**, not on the Workspace |
| 4 | A command that calls `setsid` and traps TERM, then stopped | tree **and** group signalled, TERM → 5s → SIGKILL, **survivors named** rather than rounded off (§6.3) |
| 5 | Tunnel link lost, then restored | **Connecting** one-way, **Failed** when a working link goes stale, quoting the tunnel; **no event during the retry window**; recovery with no keypress |
| 6 | All eleven Operations through `WorkspaceOperations` with no transport | frontend-independent core; the pipeline runs once per Operation, not once per call site |
| 7 | A command past 45s | **Promoted**, Handle returned before the outcome exists, `get_result` partial then final, then **Unclaimed**; `get_result` refused after the level drops |
| 8 | The same `request_id` arriving: finished / in-flight / with different arguments / after expiry | [ADR 0004](adr/0004-caller-supplied-key-for-repeat-deliveries.md) — including that a repeated `edit_file` never answers **`failed`** |
| 9 | A path argument through a symlink leaving the Root | resolve-then-check confinement, and the error **naming the symlink** |
| 10 | A Root renamed under a live Workspace | **Broken**, absent from discovery, every call rejected, re-confirm **drops to Read** |
| 11 | Runtime Stop with a promoted command running | **Uncertain**, not **Lost**; one grace period total for several commands |
| 12 | Kill the Runtime with a mutation in flight, then restart | **Lost**, and the Activity entry survives the restart to say so |
| 13 | Start with no credentials file; then one at mode `0644`; then a valid one whose key is wrong | **refusal to start** naming the file and the wizard, **refusal on a wide mode**, and a wrong key reading **Connecting** forever with the tunnel's own 401 quoted beside it — never a diagnosis and never a new state (§11.1, §11.3) |
| 14 | Start with no stored fingerprint; acknowledge; restart; then change one catalog entry and restart | **Unconfirmed** on a fresh install, cleared by the user's word alone, **not** re-raised by a restart, and re-raised by a catalog that differs — first run and re-creation proving to be one code path (§11.5) |

Scenarios 7, 8 and 11 did not exist when this handoff was scoped; 8 is the one least safe to ship
untested, since it is the only thing standing between a repeated `edit_file` and a `failed` that
lies. **13 and 14 arrived with the last decision on the route**, and 14 is the only scenario in
the table whose subject is not on this machine: it can prove that the *Connector row and detail* behave, and
nothing can prove the user actually re-created the connector.

---

## 14. Implementation work items

The order the work was built in.

1. **Repo skeleton**: the six-artifact Gradle layout, JDK 26 toolchain, version catalog.
2. **Domain types in `core-api`**: `Outcome`, `Failure`, `Uncertainty`, `Operation`,
   `ManagementAct`, `Snapshot`, `RuntimeEvent`, `OperationSpec`. (`RuntimeConfig` landed in `core`
   with item 12: no frontend holds one, and its paths are the filesystem detail `core-api` keeps
   out.)
3. **Registry and the admission pipeline**: resolve → level → confine → key → lock → Activity →
   Outcome, as one home (§9).
4. **File and search adapters** (§4).
5. **Git adapters** (§4).
6. **`run_command`, reaping, promotion, Handle, `get_result`** (§6.2, §6.3).
7. **Delivery de-duplication** (§6.4).
8. **Activity store**: append-only, retention, acknowledgement, crash recovery to **Lost** (§10.1).
9. **`mcp` adapter**: catalog rendering, `allowedHosts`, the `http://` logical URL (§7).
10. **Tunnel supervision and the Connected signal** (§8.4).
11. **`control`**: wire protocol, peer-credential authentication, the management client library
    (§9).
12. **`runtime`**: composition root, `main()`, child ownership, and **configuration sourcing** —
    the credentials file and its mode check, XDG defaults, the `config.toml` override, the
    compiled-in logical host, and the refusal to start without a credential (§11.1, §11.2).
    No longer blocked.
13. **`tui`** (§10.2), including the **Review stage list**, the Unconfirmed detail, and the first-run credential panel
    (§11.3, §11.5).
14. **The setup wizard**: a shipped script that walks the Platform-dashboard half of first run
    and writes the credentials file at `0600` (§11.6). Bash, not Kotlin, and independent of the
    six-artifact layout, so it can be built at any point.
15. **Packaging and distribution**: this was fog on the map and is execution, not a decision. The
    one choice it owed, tracking the SDK's Ktor version, is settled in §12 and ADR 0008.

## 15. Out of scope

Recorded so it is not reopened by accident, and so the boundaries are legible without the map:

- **A model or chat interface inside the dashboard**, model-provider routing, and local model
  hosting.
- **Multi-user hosting**, and initial Windows/macOS delivery. Not foreclosed — what a Windows port
  must reopen is in §12.
- **Mandatory command sandboxing.** The user selected trusted execution under their own Linux
  account (§2.4).
- **General desktop automation and host administration** beyond the workspace use cases.
- **Git mutation tools.** `run_command` reaches `git commit` when commands are enabled (§4).
- **Job handles as a separate durable store.** Promotion put a Handle in the first release, and it
  points into Activity rather than founding a second record (§6.2).
- **MCP's Tasks extension.** Unreachable over this transport, measured (§6.1).
