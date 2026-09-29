# A Workspace at None reads as absent, not as denied

`list_workspaces` omits Workspaces at None entirely rather than listing them as denied,
because listing them leaks the names and paths of deliberately withheld directories into a
conversation that leaves the machine. The same resolution also said that enforcement happens
when a call arrives, "with an error stating the Workspace's current level and the level the
operation needs" — which, for a Workspace the model names anyway, confirms that a directory
by that name exists and is being kept back, and so hands over in an error exactly what
discovery was careful not to say. A call naming a Workspace at None is therefore answered
`NoSuchWorkspace`, the same answer a name nobody ever registered gets, with the error naming
the Workspaces that *are* exposed. `LevelTooLow` is reserved for the levels above None, where
the Workspace is discoverable already and nothing is disclosed by saying so.

## Consequences

The dial's bottom notch becomes a real withholding rather than a polite refusal. A user who
sets a Workspace to None has made it invisible from the conversation's side in every
direction — discovery, errors, and the difference between the two.

A **Broken** Workspace keeps `WorkspaceBroken` and is named plainly. It is also absent from
discovery, but for an unrelated reason: the thing on disk changed identity, the Workspace was
discoverable until it did, and the frontend needs the distinct reason as its cue to offer
re-confirmation. Nothing is disclosed that the model was not already told.

The cost, stated rather than hidden: a user watching a transcript sees ChatGPT told "no such
workspace" about a Workspace plainly listed in their own TUI, and nothing in the conversation
explains the gap. That is the intended reading — the alternative explains the gap by
disclosing the withheld directory — but it is a place the transcript and the screen
deliberately disagree, and a frontend explaining the dial should say so.

`Failure` carries both reasons as distinct sealed subtypes, so this is a decision about which
one the pipeline selects, not about what the type can express. Reversing it costs nothing in
code and re-opens the leak, which is why it is written down rather than left to whoever
writes the admission check.
