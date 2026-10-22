# Boot startup is an explicit user choice, separate from the Runtime's life

Both frontends offer **Start Runtime at boot**, off by default, so a release user can make
Proxenos available before login without hand-editing systemd files. A user unit with account
lingering keeps the Runtime under the user's existing authority and preserves its management
socket; a root Runtime or a second installation would change those boundaries.

Enable and Disable configure future boot startup without interrupting a running Runtime.
Disable does not remove lingering, because other user services may depend on that account-wide
setting. Stop remains a deliberate clean exit, so supervision uses `Restart=on-failure` rather
than `always`. A registered manual Start uses systemd; failed supervision never silently starts
a second detached Runtime. Existing detached processes are not interrupted or adopted.

This replaces the former policy of never offering automatic startup. Before enabling it, the
frontends explain that saved Access Levels and Connect/Disconnect state apply unattended, and
that the installation and configuration must be accessible before login.
