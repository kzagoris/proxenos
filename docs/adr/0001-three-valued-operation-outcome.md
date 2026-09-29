# Mutating operations report three outcomes, not two

The OpenAI Secure MCP Tunnel cannot promise exactly-once delivery: the control-plane
package states that a crash after polling leaves drained work awaiting connector timeout,
and a response post that is never acknowledged says nothing about whether the write on disk
happened. A killed or interrupted mutation can be neither undone nor safely replayed. So
every mutating tool reports `ok`, `failed`, or `uncertain`, where **`failed` carries a hard
guarantee that nothing on disk changed** and `uncertain` means the effects are unknown —
a timeout, a cancellation, or a Root that went Broken mid-write.

## Considered options

Two outcomes, success and failure, is what MCP conventions and every client expect. It was
rejected because it forces every ambiguous case into one of the two buckets, and both
choices are wrong: reporting a timed-out write as `failed` invites the model to retry a
half-finished mutation, and reporting it as `ok` claims something we cannot know. The
distinction only earns its keep because `failed` is then strong enough to retry against.

## Consequences

`uncertain` is not an MCP convention, so a client may flatten it back into a plain error and
lose the distinction. The mitigation is wording: the result text has to say *effects
uncertain, do not retry* in language a model acts on, rather than relying on a status field
being understood. Whether this survives the round trip to ChatGPT is unverified and is one
of the four things the validation spike measures.
