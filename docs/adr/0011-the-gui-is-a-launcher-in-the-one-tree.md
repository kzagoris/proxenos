# The GUI is a launcher in the one tree, not a jpackage image

`bin/gui` lives beside `bin/runtime` and `bin/tui`, over their shared `lib/` and, in the
linux-x64 archive, `jre/`. It uses that Java runtime unconditionally and names the Runtime
beside it through a system property, resolved through launcher symlinks. A second app-image
would duplicate Java, create a second installation to upgrade and make Runtime discovery
depend on how the two installations happen to be arranged.

This extends ADR 0010's archive distinction: only the linux-x64 archive carries the GUI,
because its Skiko native libraries are for x86_64. The portable archive retains Runtime and
TUI on any architecture with Java 26. The local development installation includes the GUI;
the two archives therefore share the Runtime and TUI tree rather than identical frontends.

The launcher defaults to Skiko's software renderer and honours `SKIKO_RENDER_API` when set.
Repeated closes of the Compose Desktop trial left OpenGL processes alive after their windows
closed; the software renderer exited cleanly. Closing a GUI cancels only its own coroutine
scope, without waiting for an act; it never owns or ends the Runtime.
