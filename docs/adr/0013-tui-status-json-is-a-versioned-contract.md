# `tui status --json` is a versioned contract an agent reads

A coding agent that installs and sets up Proxenos has to know where things stand: whether a
Runtime answers, what the Tunnel and Connector read, which Workspaces exist at which Access
Level, and whether this machine has the launcher, `tunnel-client` and owner-only credentials it
needs. `tui --dump` prints the home screen as text written for tests and people, and scraping it
would break whenever a heading moves. So `tui status --json` prints the same facts as one JSON
object, and its shape is a contract.

The object carries `"version": 1`. Within a version a field may be added; none is renamed,
retyped or dropped, so a skill written against version 1 keeps reading every later version-1
answer. Changing what an existing field means takes a new version. Field names and values are
`CONTEXT.md`'s terms (`workspaces`, `root`, `accessLevel`, `broken`, the Tunnel's state names),
because the agent explains them to a user who will meet the same words in the frontends.

Like `--dump`, it never starts the Runtime: an agent asking where things stand must not change
them by asking. Readings only a Runtime can give are `null` when none answers, never guessed.
The runtime key never appears; the saved tunnel ID does, because the user enters it in ChatGPT
and it is not a secret.

Beside it, `tui check`, `tui await-listing` and `tui register` let the same agent check the
machine, wait for ChatGPT's Root listing (ADR 0012's evidence, read from the Runtime's Activity
rather than the GUI's setup progress, which they never write) and register a Workspace at Read
while the Runtime runs. Their sentences are for people; their exit status is what an agent acts
on. Nothing here raises an Access Level, acknowledges the connector (ADR 0007) or writes
credentials: each stays where a person does it.

## Considered options

**Stable `key: value` lines** are easy to grep but have no way to say "absent" apart from an
empty string, and nesting the Tunnel's verbatim complaint would need an escaping convention of
our own.

**Making `--dump` the contract** would freeze the screen's wording, which is the one thing the
frontends must stay free to improve.
