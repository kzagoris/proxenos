# The core abstracts nothing beneath itself

The workspace core sits on a filesystem, `git`, child processes, a clock and a registry file,
and it defines no interface for any of them. There is no `FileSystem`, no `Clock`, no
`ProcessLauncher`, no `Store`, no `TunnelClient` port. It calls `java.nio.file` and
`ProcessBuilder` directly, and the two things that would otherwise be faked — the command
timeout and the tunnel executable's path — are plain configuration, so a test sets a 200 ms
timeout and points at a stub script. The core's only interfaces are the two it presents
upward, to the MCP catalog adapter and the management seam.

## Considered options

Ports and adapters is the expected shape, and it was rejected on the rule that one adapter
means a hypothetical seam and two means a real one. Every candidate port here has exactly
one implementation: one filesystem, one `git`, one official tunnel executable, one registry
file. Under the deletion test each port vanishes without complexity reappearing anywhere —
they would be pass-throughs — while the behaviour that genuinely is complex lives in their
composition, which is precisely what a per-port seam stops you testing as a whole.

An in-memory filesystem was the strongest single case and fails on substance rather than
principle. The rules this core exists to enforce are symlink resolution against a real path,
atomic rename preserving permission bits, a child process's working directory, and a
mutation lock keyed on a resolved real path. A fake filesystem models none of them
faithfully, so those tests would pass against a fiction.

## Consequences

Tests are slower than pure unit tests, they need `git` present on the machine, and
supervision tests need a stub executable that can be told to crash. Accepted deliberately:
they exercise what actually ships.

Time is the sharpest edge. With no `Clock`, anything genuinely time-dependent must be
expressed as a configurable duration rather than waited out; a test that cannot be written
that way is the signal to revisit this, not a reason to sprinkle in a seam.

This is a Linux-first decision and the place a Windows port would reopen it: a second
operating system is the first real second adapter, and the seam gets designed then, against
two known cases, rather than guessed at now against one.
