# Optional Runtime startup before login

## Outcome

A user who downloads a Proxenos release can enable **Start Runtime at boot** from either
frontend. After a reboot the Runtime starts before the user logs in, under that user's Linux
account. Opening the GUI or TUI attaches to that Runtime; closing either frontend changes
nothing about it.

Automatic startup is off by default. Enabling it configures future boot startup without
interrupting the current Runtime. Disabling it prevents future boot startup without stopping
the current Runtime. **Stop Runtime** remains a separate action.

This design replaces the existing product policy that automatic startup is never offered.
It preserves the meanings in `CONTEXT.md`: Runtime, Attached, Connected, Disconnect, Stop,
Workspace, Access Level, Operation and Activity are unchanged.

## Scope and ownership

- Support Linux installations with systemd and logind. Existing manual startup continues to
  work when automatic startup is unavailable.
- Use one `proxenos.service` user unit per Linux account, plus logind lingering for that
  account. Do not introduce a root Runtime, a system-level Runtime unit or a second account.
- The setting belongs to the local installation, so its status and controls work even when
  no Runtime answers. It is not a management act sent to the Runtime.
- Shared startup inspection, registration and launch selection live in `frontend`. The GUI
  and TUI render the same structured results. Neither gains a dependency on `core` or
  `runtime`, and neither contains its own systemd policy.
- Retain the control socket as the management seam. No new Runtime protocol is required.
- The unit starts only `bin/runtime`; it never opens a frontend.

## User interface

Both frontends expose the setting in **Connection → Runtime**, alongside the existing Start
and Stop controls. The TUI uses its existing Runtime detail for the equivalent location.
Do not add a new page or a required onboarding step.

The main label is **Start Runtime at boot**. Its explanation is:

> Run Proxenos when this machine boots, before you log in, using your saved Workspace
> Access Levels. Your saved Connect or Disconnect choice is preserved.

The enabling flow also explains, once, that starting before login enables the account's
background user manager and can start its other enabled user services. Use the operating
system's authorization flow when local policy requires it; do not collect a password in a
Proxenos field or assume that every machine requires administrator authentication.

Show automatic startup independently of Runtime and Tunnel state:

- **Off:** future boot startup is disabled; the Runtime may still be running.
- **On:** the managed unit is enabled, lingering is enabled and its registration is valid.
- **Needs attention:** configuration is incomplete, the registered launcher is missing or
  different, the unit failed, or the observed settings disagree.
- **Unavailable:** required system services or authorization are unavailable. Say why and
  leave manual Start available where it can work.

Use **Enable** and **Disable** actions. When the launcher registration needs updating, offer
**Use this installation** and show its location before applying it. This changes registration
only; it does not replace files or restart the Runtime.

After disabling, say **Automatic startup is off. The Runtime was left unchanged.** Do not
claim that a Runtime is running unless the control socket or a fresh observation establishes
that fact. Explain in the detail that account lingering is left enabled because other user
services can depend on it.

## Registration and enablement

Use the distribution's existing `docs/systemd/proxenos.service` as the unit definition to
maintain and ship. Generate the installation-specific launcher and path settings through the
shared implementation, rather than asking the user to copy and edit the example.

1. Resolve the configured Runtime launcher using the existing discovery policy. Resolve
   launcher symlinks to its distribution tree, preserving that tree's selected installation
   path rather than inventing a second installation. Require an executable launcher.
2. Inspect the existing unit before changing it. An existing managed registration is
   idempotent. A differently registered installation must be shown as such. An unknown or
   externally customized unit must not be silently overwritten; report the conflict and
   preserve it.
3. Validate that the release can start with a noninteractive environment. Preserve the
   selected XDG configuration and state locations, explicit Proxenos path overrides, and
   portable Java selection where needed. Use an explicit allowlist of nonsecret path
   settings. Never copy the full shell environment into the unit or user manager.
4. Write the managed unit atomically into the user's systemd configuration directory and
   reload the user manager. Quote and escape unit values for systemd, including spaces,
   percent signs and literal special characters. Do not interpolate them into shell code.
5. Enable account lingering through logind's supported authorization mechanism, then enable
   the unit under the user manager's `default.target`, without starting or restarting it.
6. Read back the actual enablement and lingering state before reporting On. Enabling does
   not prove that a future boot will succeed; it reports the configuration installed.

The operation must leave an existing Runtime and all running Operations untouched. A
frontend normally already starts or attaches to a Runtime when opened; enabling automatic
startup does not perform a second launch or attempt to adopt that process into systemd.

If a step fails, report which change succeeded and which failed. Restore Proxenos-owned
unit files and enablement changed by this attempt where that can be done without touching
the Runtime. Never undo pre-existing settings, disable lingering as rollback, or claim Off
or On when the resulting configuration has not been inspected. A retry must safely converge
on the requested state.

## Disablement

Disable the user unit without `--now`. Keep the managed unit installed so later manual Start
can still use it. Do not stop or restart the Runtime, alter saved Connect/Disconnect state,
change an Access Level, delete credentials or delete Activity.

Do not disable lingering, even if this feature originally enabled it: it is account-wide,
and other services may have started depending on it in the meantime. Disabling this setting
controls boot startup only; an existing systemd-managed invocation retains its crash
supervision until it ends.

## Runtime lifecycle

- With no registered unit, preserve today's detached launch through `setsid`.
- With a valid managed unit registered, future manual Start uses the unit, whether its boot
  enablement is On or Off. If the Runtime already answers, attach without starting anything.
- Do not fall back to a second detached Runtime when starting a registered unit fails. Report
  the failure and its actionable diagnostics. Preserve single-instance locking as the final
  guard against concurrent starts from both frontends.
- Keep `Restart=on-failure` and `RestartPreventExitStatus=78`. A deliberate Stop exits cleanly
  and must not restart. Missing credentials or duplicate-instance refusals must not create a
  restart loop. Never use `Restart=always` with the existing management Stop.
- Keep the existing management Stop and its shutdown behavior: running Operations end as
  Uncertain, the tunnel child is ended and sockets and locks are released. Verify that the
  supervised process exits successfully and stays stopped. Start remains available after it.
- Stopping does not disable future boot startup. The Runtime stays stopped until an explicit
  Start, opening a frontend that follows the existing start-if-absent policy, or the next boot.
- Closing every frontend or logging out does not stop a supervised Runtime. Startup does not
  change its saved Connect/Disconnect choice or any Workspace's Access Level.
- An unavailable network at boot is not a reason to open a frontend or report Connected.
  Preserve existing tunnel retry and measured connection-state behavior.

## Releases, upgrades and environment

Register the launcher in the selected installed release tree. Recommend the existing stable
installation location `~/.local/opt/proxenos`, while supporting another explicitly selected
location. Do not copy the release elsewhere as a side effect of enabling startup.

Replacing a release at the same location preserves the unit registration. The existing
upgrade flow stops the Runtime before replacing files; this must still leave it stopped
while replacement takes place. Moving the tree requires updating the registration through
**Use this installation**. Removing the application requires disabling automatic startup and
removing its managed unit as part of the documented removal procedure; ordinary Disable
retains the unit and all user data.

The bundled archive continues to use its own `jre/` (ADR 0010). The portable archive must have
a Java selection that also works under systemd, without sourcing a shell profile. Saved
credentials remain only in the existing owner-only credentials file. Unit files, diagnostics
and status output never contain the runtime key.

Commands run with the environment available to the Runtime's user manager, plus the selected
nonsecret startup settings. Do not promise the environment of an interactive terminal.

Boot startup requires the launcher, Java, tunnel executable, configuration and credentials to
be accessible before login. Login-unlocked or login-mounted storage can make it unavailable;
do not promise that lingering unlocks it. A Workspace whose Root is unavailable follows
existing Broken behavior. Clearly explain this limitation, and identify known incompatible
setups without claiming that a successful current-session check proves boot availability.

## Diagnostics and compatibility

Inspect systemd and logind state rather than maintaining a second saved boolean. Distinguish
boot enablement, supervision of the current Runtime and the Runtime's observed state. Read
current unit diagnostics on demand, with bounded output and secret-safe handling.

Refresh startup status when opening the Runtime detail and after an action, so another
frontend or an external `systemctl` change is reflected. Serialize mutations inside each
frontend and make concurrent operations from different frontends converge safely; final
status always comes from reinspection.

Expose the same information additively in `tui status --json`, retaining version 1 and all
existing fields and meanings (ADR 0013). Reading status, `tui check`, and drawing a snapshot
must not install, enable or start anything. Do not add a separate command family solely for
this feature; the interactive TUI provides the required control.

## Verification

Tests must exercise observable behavior, not generated text snapshots or a mock's expected
sequence of subprocess calls. Use process and filesystem seams only where they expose real
failure modes such as quoting, wrong paths, incomplete registration and denied authorization.

Verify these behaviors using the installed release tree and an isolated systemd/logind
environment:

1. A fresh unpack, desktop-entry installation and ordinary frontend launch leave automatic
   startup disabled. Both frontends expose the same state and changes.
2. Enabling while a Runtime has an Operation in progress leaves that Runtime's start identity
   and the Operation unchanged. Closing and reopening frontends preserves them.
3. After enablement, a real isolated boot starts the Runtime before any login session exists.
   Later GUI and TUI launches attach to that same Runtime. Logout leaves it running.
4. Disable while running leaves the current Runtime untouched; the next isolated boot does
   not start it. Lingering and unrelated enabled user services are preserved.
5. Stop under supervision ends the Runtime and its children without a restart. A later manual
   Start works. A crash is restarted; an exit-78 refusal is not repeatedly restarted.
6. A detached Runtime that predates registration is not duplicated or interrupted. After a
   deliberate Stop, the next Start is supervised. Concurrent frontend starts leave only one
   Runtime.
7. Denied authorization, conflicting external units, unavailable system services, missing
   launchers and partial failures produce accurate status and recover on a safe retry.
8. Installations with spaces and special characters, selected XDG locations, configured
   sockets, a portable Java selection, and an upgrade at the same location remain usable.
9. Saved Disconnect and Access Levels survive boot startup; a network arriving later follows
   the existing Tunnel state behavior. Status inspection has no launch or setup side effects.

Run desktop/session integration checks inside omabox as required by the repository's desktop
workflow. A contained desktop with a user manager alone does not establish pre-login boot
behavior: use an isolated VM or equivalent boot-capable environment for that acceptance
criterion. Do not reboot or configure the developer's machine for verification. Record
explicitly if actual boot verification is unavailable.

Run `./gradlew build`, the applicable frontend harnesses and release-artifact checks for the
implementation. Credentials and throwaway environments stay outside the public checkout.

## Documentation changes with implementation

Record an ADR for offering opt-in boot startup and choosing a user unit with lingering.
Update README, installation/upgrade/removal instructions, unit comments, existing no-autostart
wording and the frontend specification so they agree. No change to the domain glossary is
needed. Preserve ADRs 0010, 0011 and 0013: one release tree, frontends that do not own the
Runtime, and an additive status contract.

## Primary references

- [loginctl: enable-linger](https://www.freedesktop.org/software/systemd/man/latest/loginctl.html)
  describes starting the user manager at boot and keeping it after logout.
- [systemd.special: special user units](https://www.freedesktop.org/software/systemd/man/latest/systemd.special.html)
  describes the user manager's startup targets.
- [systemctl: disable](https://www.freedesktop.org/software/systemd/man/latest/systemctl.html)
  distinguishes disabling from stopping a running unit.
- [systemd.service: Restart](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html)
  describes crash restart, clean exit and explicit stop behavior.
- [homectl](https://www.freedesktop.org/software/systemd/man/latest/homectl.html)
  describes activation of managed homes and its dependency on authentication.
