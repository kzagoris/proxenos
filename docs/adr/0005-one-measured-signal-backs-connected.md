# One measured signal backs Connected, and the tunnel supplies the words

`Connected` is the only thing the status bar promises about the outside world, and the two
signals that look like they answer it do not. `/readyz` is a readiness handler that never
references the poller — it walks cloudflared gates, OAuth discovery and the local MCP startup
probe — so it answered `200 ready` while every control-plane poll was failing 401 against a
fake key, exactly as its source says it will. `tunnel-client doctor`'s `mcp_server_reachable`
check dials TCP and therefore fails on DNS for a Unix-socket target. So the Runtime reads
**`commands_poll_last_successful_timestamp_seconds`** from the tunnel client's `/metrics`,
bound to a Unix socket, and derives all four states from that one number plus its own start
time: **Connecting** when no poll has ever succeeded since this Start, **Connected** when the
last success is newer than one poll cycle plus a margin, **Failed** when a success has gone
stale, and **Disconnected** only ever from the user's intent. The child's most recent warning
line is retained and attached verbatim to any state that is not Connected, as text — never as
a state input.

The staleness threshold is **derived from the child's own configuration** rather than fixed.
The poll is a long poll: a 30-second server wait plus a 5-second guardrail by default, both
configurable and capped at ten minutes. A hardcoded 60 seconds would report a permanently
lost tunnel the moment anyone raised the wait, and the Runtime launches the child, so it
already knows both numbers.

## Considered options

**Driving the state machine from the child's log lines** was the first design and is the one
this rejects. The poller logs a clean edge pair — `poll failed; backing off` at Warn, carrying
`status_code`, `error_code` and `mitigation`, and `poller recovered; polling operational` at
Info — which is richer than the gauge: it is instant rather than a cycle late, and the presence
or absence of `status_code` separates an HTTP rejection from a transport failure, which
`/metrics` cannot do at all (`error_kind` collapses a 401 and a DNS failure both into `other`).
It was rejected because those strings are undocumented internals of someone else's Go source.
A reword upstream would stop the status bar working with nothing failing loudly, whereas the
gauge is scraped by name by the client's own `health` subcommand and so is load-bearing for
its author too. Logs keep the one job the gauge cannot do — supplying words — where a reword
degrades the reason text and leaves the state correct.

Logs also cannot see success at all on a clean start: `poller recovered; polling operational`
fires only *after* a failure, and the per-cycle lines are Debug. A Runtime that starts and
works perfectly logs `poller started` and then nothing.

**Classifying the failure ourselves**, so the bar could say "wait" versus "act" in its own
voice, was rejected with it. A tunnel deleted server-side is not distinguishable from a wrong
or under-privileged key: the poll endpoint documents no 404 and no 410, and the most likely
landing spot for both is 401 `tunnel_use_forbidden`. The classification we could build would
be coarse exactly where it mattered most, so the user reads the tunnel's own `mitigation`
string and judges. This is why `Failed` carries a quotation rather than a diagnosis.

**Treating a sustained outage as a state the Runtime gives up on** cannot be implemented as
written. `tunnel-client` never gives up: the poller backs off 200ms→10s with jitter and has no
failure threshold and no fatal path, so a bad key loops roughly every ten seconds forever with
the process alive and `/readyz` still answering 200. Supervision restarts a child that *dies*,
and poll failure never kills it. This retires the "bounded backoff before giving up" and
"supervision has stopped retrying" readings of earlier drafts.

## Consequences

`Connected` is now a narrower claim than "ChatGPT can reach the Runtime", and the glossary says
so. A connector deleted in the ChatGPT UI leaves the link up and the Runtime `Connected`, and
nothing on this machine can see it; the interface states that once, in the Runtime detail, and
the status bar stays one line.

A bad key on first run reads `Connecting` indefinitely, with the 401 and its mitigation text
beside it, rather than escalating. That is honest — no poll has ever succeeded — but it lands
on first-run onboarding, which is the moment the user knows least.

Whether the gauge is absent or zero before the first successful poll was not established from
source and deliberately does not matter: absent, zero and older-than-Start are all the same
rule, and all three mean `Connecting`.

`main` adds a `/health/control-plane` component carrying `consecutive_failures`,
`failure_category` and `next_retry` — this decision's question answered in machine-readable form,
including the rejection-versus-outage split. It is in no tagged release as of v0.0.14, and
older runtimes 404 the route, which makes it a clean feature probe if it ships.

**Amended by [ADR 0009](0009-each-stage-is-measured-on-its-own.md):** the caveat is stated in
the **Tunnel** detail, and the status line carries **Runtime**, **Tunnel** and **Connector**
segments. The claim itself — Connected is the link, not ChatGPT's reach — is unchanged.
