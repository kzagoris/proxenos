# Each connection stage is measured on its own, and the unmeasured reads as unmeasurable

Review shows the chain a ChatGPT call travels — **Runtime**, **Tunnel**, **Connector** — as three
rows, and the compact status line carries the same three segments in the same order. Each stage is
derived from its own signal and no stage is inferred from another: the Runtime reads this
dashboard's attachment (`Attached`, or `Not running` with the reason), the Tunnel reads the
Runtime's own `RuntimeState`, and the Connector reads the fingerprint acknowledgement. While the
Runtime is not running there is nothing to measure below it — the link is the Runtime's to hold and
the catalog is the Runtime's to serve — so those stages read **Can't tell**, dim and never red.

## Considered options

**One connection light**, green when a ChatGPT call could land and red when it could not, was the
obvious shape and is the one this rejects. It would have to derive itself from parts that measure
different things, and it would be wrong in both directions: red for a `Disconnected` tunnel the
user chose themselves — a decision, not a fault — and green on a `Connected` Runtime whose
connector was deleted in ChatGPT, which is the claim ADR 0005 and ADR 0007 exist to refuse.

**Hiding the stages below a Runtime that is not running** is honest but loses the map: after a
reboot the screen would say "not running" and nothing about what comes back when it runs, leaving
`[S]` as the only thing on it rather than the first link of a chain. Showing the same stages red
was rejected for the reason the first option was: nothing failed, nothing was measured, and the
colour would be a diagnosis.

**Can't tell** is deliberately not a new `RuntimeState`. It never reaches the core, appears only
in a frontend, and is derived from the absence of a snapshot; the states themselves and their
triggering conditions are unchanged.

## Consequences

The capitals convention is recorded here because nothing else documents it: a lowercase key is
what is done often and can be undone (`[d]` connect/disconnect, `[a]` acknowledge), and an
uppercase key is an act that changes the Runtime's life or records a fact (`[X]` stop, `[C]`
confirm, `[R]` re-confirm) — with `[S]` capital partly to keep it apart from `[s]`, stop-a-command.
Every stage's action lives in the stage detail it acts on, and the Review list itself carries
none: only the global keys do there what they do everywhere — `[q]` closes, `[i]` opens Review,
and `[S]` starts a Runtime that is not running, which the row's own reason line points at.

The status line and the rows are drawn from one reading function, so the words cannot drift apart
between them. Review does not draw the status line: on that screen the rows are the status.
