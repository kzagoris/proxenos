# Troubleshoot

**Done when** the symptom the user reported is gone, shown either by `bin/tui status --json` or
by ChatGPT doing what failed. If the cause turns out to be on OpenAI's side, done is telling the
user exactly which setting to check and where.

Start from `bin/tui status --json` and work down. The first branch that matches is usually the
cause.

## `bin/tui` will not run

- `UnsupportedClassVersionError … class file version 70.0`: the portable archive found a Java
  older than 26. See [java.md](java.md).
- `Unexpected argument: status`: this release predates `status`, `check`, `register` and
  `await-listing`, so upgrade it first ([upgrade.md](upgrade.md)). Until then,
  `bin/tui --dump --frames 1` prints the home screen as plain text.
- `XDG_RUNTIME_DIR` missing: the session is not a normal login session. See `docs/INSTALL.md` §1.

## Requirements not met

Each unmet entry in `requirements` says what to do in its `words`. In short:
- `tunnel-client`: run `bin/install-tunnel-client`.
- `credentials`: back to [connect.md](connect.md) step 1; a file others can read gets `chmod 600`
  once the user agrees.
- `runtime`: the tree was split up; unpack it whole again.

## Runtime absent

Run `bin/tui start`. When it refuses, its words are the Runtime's own reasons, and the Runtime's
output is in `$XDG_RUNTIME_DIR/proxenos/runtime.log`. Read the end of that log; it holds no key.
A `config.toml` refusal names the file and line.

## Tunnel

`tunnel.complaint` is the tunnel client's own words, verbatim. Quote it to the user rather than
guessing at it.
- `Connecting` with no `complaint` after a minute: the tunnel client has not managed a poll
  yet. Its words appear here after one long-poll wait. Meanwhile check outbound HTTPS and the
  end of `$XDG_RUNTIME_DIR/proxenos/runtime.log`.
- `Connecting` with a 401-style complaint: the tunnel ID or runtime key is wrong, the key lacks
  Tunnels Read + Use, or the tunnel was deleted. These look identical from here, so have the user
  check each in Platform. A new key is saved as in [operate.md](operate.md#rotating-the-runtime-key).
- `Failed`: a link that worked has gone stale. The tunnel client keeps retrying by itself. Check
  outbound HTTPS from this machine first.
- `Disconnected`: someone took the tunnel down in a frontend. It reconnects from the GUI's
  **Connection → Tunnel**, or `i` then `d` in the TUI.

## Connected, but ChatGPT fails

Nothing on this machine can see the ChatGPT side, so ask the user what ChatGPT showed:
- **The tunnel isn't offered in the plugin form**: it is not associated with that ChatGPT
  workspace, or the user lacks Tunnels Read + Use.
- **An OAuth error**: the plugin was created with OAuth. Recreate it with **No authentication**.
- **No Workspaces, or one missing**: it is at None, or `broken`. See
  [operate.md](operate.md#workspaces).
- **A write or command refused**: the refusal names the level needed. The user raises it in a
  frontend if they mean to.
- **Tools missing or stale after an upgrade**: `connector` reads `Unconfirmed`. ChatGPT keeps the
  catalog it saw when the plugin was created, so the user deletes and recreates the plugin, then
  confirms in a frontend. Then run `await-listing` again ([connect.md](connect.md) §4–5).

## The desktop app

- It will not open: read `$XDG_RUNTIME_DIR/proxenos/gui.log`. It needs x86_64, X11 or XWayland
  (`DISPLAY`), libGL, libX11 and fontconfig, and ships only in the linux-x64 archive. Native
  Wayland without XWayland is unsupported.
- Wrong size: set `gui_scale = 1` (or `1.5`, …) in `~/.config/proxenos/config.toml`, then reopen.

## Still stuck

`docs/INSTALL.md` and the README's troubleshooting table cover the rest. For a bug, open an
issue at <https://github.com/kzagoris/proxenos/issues> with the `tui status --json` output; it
holds no key. Security problems go privately, as `SECURITY.md` says.
