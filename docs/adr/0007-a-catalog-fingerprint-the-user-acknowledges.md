# A catalog fingerprint the user acknowledges

ChatGPT snapshots a developer-mode app's tool catalog and never refreshes it, and Disconnect in
its UI deletes the plugin definition outright rather than detaching it. Both were measured, not
read. Nothing on this machine can see either fact: a connector deleted in ChatGPT leaves the
link up and the Runtime `Connected`, and a connector still pointed here may be serving a catalog
we stopped shipping two versions ago. The one thing the Runtime does know is **what it is
serving right now**, because the core owns the catalog as data — name, argument schema, required
Access Level — rather than as a list of `addTool` calls inside an adapter.

So the Runtime hashes that data into a **catalog fingerprint**, and persists the fingerprint the
user last confirmed their connector was built against. When the stored fingerprint is absent or
differs from the current one, the connector is **Unconfirmed**, and a banner under the status bar
carries the literal re-creation steps until the user acknowledges it — delete the app in ChatGPT,
New Plugin, select the tunnel, and choose **No Auth**, because the dialog defaults to OAuth and
that fails before any tool call. Acknowledging stores the current fingerprint.

This makes first run and re-creation **one code path**, which is the honest shape: on a fresh
install there is no stored fingerprint, on an upgrade there is a different one, and the user does
the same thing in the same dialog either way. The catalog is flat and static, so `Unconfirmed`
cannot be reached by registering a Workspace or moving an Access Level — only by installing a
version of this product whose catalog differs. It is a rare event that is invisible and expensive
when missed, which is the profile that earns a persistent banner rather than a line in a release
note.

## Considered options

**Saying nothing, and putting it in release notes** was the alternative, and it is what most
products would do. It was rejected because the failure it prevents is silent on both ends: the
user's ChatGPT calls a tool that no longer exists, or worse, calls an old one whose meaning
changed, and the Runtime answers exactly as asked. There is no error to read and nothing in
Activity that looks wrong. A release note is read once by a user who has already upgraded and is
looking at a working screen.

**Detecting it** is not available and should not be simulated. The design considered inferring
staleness from what ChatGPT calls — a conversation that never touches `get_result` might be
running an old snapshot — and rejected it for the same reason the status bar carries no traffic
reading: silence means two opposite things. An idle afternoon and a dead connector are the same
data. The fingerprint deliberately measures *us* and asks the *user* about them, rather than
guessing at a third party from its absence.

**Making the acknowledgement an Activity entry** was considered, since `Acknowledged` already
exists there and has the right append-only shape. It was rejected because Activity is the account
of Operations, and nothing about creating a connector in a browser is an Operation: it has no
Workspace, no outcome, and no Origin. The fingerprint is Runtime state persisted beside the
registrations. It borrows the *shape* of `Acknowledged` — a recorded fact, not a cleared flag —
without borrowing its home.

## Consequences

One more thing is persisted, and it is the only persisted fact in the design that is about
something outside this machine. It is also the only one a user can make wrong by lying to it:
acknowledging without re-creating the connector stores a fingerprint that claims a snapshot the
user never took, and the product will not mention it again. That is accepted — the alternative is
a banner nobody can ever dismiss, which trains the user to ignore the one row of the screen that
is there to interrupt them.

A fresh install now has something to say before anything is registered, which the TUI's settled
feed-first shape did not previously have a place for. The banner sits under the status bar and
above the running-work band, present only while `Unconfirmed`.

`Unconfirmed` joins the glossary next to `Acknowledged`, and is deliberately not a Runtime
connection state: a Runtime can be `Connected` with an `Unconfirmed` connector, which is exactly
the situation ADR 0005 said this machine cannot see and this one declines to hide.

**Amended by [ADR 0009](0009-each-stage-is-measured-on-its-own.md):** the banner became the
**Connector** row in Review, and the literal steps moved to its detail. The fingerprint, the
acknowledgement, and what `Unconfirmed` means are unchanged.
