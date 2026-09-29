# A mutating call carries a caller-supplied key

The transport mints repeats nobody asked for. When a call goes unanswered past the tunnel's
deadline, the tunnel client delivers it to the local server again and the tool body runs in
full — and on the Unix-socket transport this project uses, that repeat is byte-identical to
the original: same JSON-RPC id (`0` on every call), same `_meta`, same arguments, no marker
at all. Reproduced deliberately twice; one session logged 20 executions of a tool body
against roughly ten user requests, and one prompt was caught executing the same 120-second
operation four times. Nothing in the frame distinguishes the repeat, so the key can only
travel in the tool arguments: `write_file`, `edit_file` and `run_command` each take a
required `request_id`, and the Runtime answers a repeat of a key it has already seen with
the first Delivery's reply instead of doing the work a second time.

This is the same mechanism `file-mcp` already ships against this transport, down to the
4096-key quota.

## Considered options

**A Runtime-derived fingerprint** over `(workspace, tool, arguments)` asks nothing of the
model and was rejected because it cannot tell a transport repeat from a deliberate one.
Running `make` twice on purpose is the same bytes as a repeat of running it once, and
suppressing the second is a silent wrong answer. The caller-supplied key is also the better
fit for the mechanism: the tunnel mints the repeat, so a key the model wrote once is
reproduced verbatim by the very thing doing the repeating.

**Putting the key on `run_command` alone** and letting the file tools protect themselves is
the trap that forced the decision wider. `edit_file` is exact-match-once, so a repeat finds
zero matches and returns `failed` — the one outcome ADR 0001 defines as *nothing changed,
therefore safe to retry* — after the first Delivery already made the change. The
self-protection manufactures a false guarantee rather than safety. The seven read-only
entries and `get_result` take no key: repeating a read costs work and nothing else.

**MCP's Tasks extension**, the standard shape for this, is unreachable over this transport
(ADR 0003).

## Consequences

`failed` keeps its guarantee intact, and keeps it precisely because a repeat is never
answered with `failed` — not by the `edit_file` zero-match path, which the key now
intercepts, and not by a refusal worded as failure.

A repeat of a finished Delivery returns the first reply verbatim, which is how an
**Undelivered** entry settles: the transport repeating a call is a second delivery window
for an answer that was lost, and using it turns a completed-but-unreported mutation into a
reported one. That resolution is an appended fact against the Activity entry, never an edit
to it.

A repeat of a **Promoted** command returns the same **Handle**. Without the key, promotion
doubles the exposure rather than reducing it: a repeat minted at the deadline would start a
second command under a second Handle, with the first still running.

A repeat arriving while the first Delivery is still in flight waits on it and is answered on
its own, later deadline — a second delivery window rather than a second execution, capped so
that a first Delivery still running as the repeat's own budget runs out yields `uncertain`.
Under the 45-second budget a slow `run_command` is Promoted before that window opens, so
this path fires when the ceiling falls below the budget (it was measured at 61s and observed
to vary) or when a file mutation waits on the mutation lock.

The same key with different arguments is refused as `failed`, and so is a missing one — both
as tool results, since ADR 0001 reserves protocol errors for a malformed call or an unknown
tool. Delivery records are kept **10 minutes past the reply**, not past the outcome, since a
Promoted Operation's reply long precedes its outcome; they are bounded at ~1024, evicted
oldest-first. The bare key outlives its record, bounded at 4096, so a key reused after
expiry is refused rather than silently re-run. Keys are unique Runtime-wide: a collision
across Workspaces then lands on the different-arguments refusal, which is the safe
direction.

A first Delivery answered `failed` releases its key rather than keeping a record of it.
`failed` means nothing changed, so there is nothing a second execution could do twice, and a
key held against a refusal — at capacity, a level since raised — would answer the very repeat
that refusal invites with the refusal again. A repeat already waiting on it claims the key
afresh and is answered as a Delivery of its own, so a `failed` is never handed over as the
recorded result of an operation performed.

Nothing is persisted across a restart, and nothing needs to be. `tunnel-client` is the
Runtime's child, so a restart kills the transport and no repeat survives to arrive; the
in-flight Operation is **Lost** and has no reply to hand over.

The store is not a second account of what happened. It holds a key, the Operation it names
and the reply that was sent, and re-executes nothing — Activity remains the account, and
remains a reporting mechanism only.

Because the key is model-supplied, the tool description is the only lever on a repeat the
model initiates itself, and it is worded as an instruction rather than a field description,
for the same reason ADR 0001 requires `uncertain` to say *effects uncertain, do not retry*
in words a model acts on.
