---
name: proxenos
description: Proxenos (Linux) lets ChatGPT read, edit and run commands in chosen local directories. Use to install, set up, upgrade, fix or remove it, or when the user wants ChatGPT to reach files on this machine.
---

# Proxenos

Proxenos runs a background **Runtime** that answers ChatGPT's tool calls through the OpenAI
Secure MCP Tunnel. Each registered **Workspace** (one local directory, its **Root**) has one
**Access Level**: None, Read, Write or Command. The desktop app (`bin/gui`, x86_64 only) and the
terminal dashboard (`bin/tui`) manage it. Use these terms with the user: they are the words the
app shows.

You do the machine's side of the work. The user does what only an account holder can: the OpenAI
Platform dashboard, the runtime key, the ChatGPT plugin form, and every Access Level above Read.
Each phase reaches a **hand-off**: tell the user exactly what to do next, then wait for them.

## Ground rules

- **The runtime key stays with the user.** They type it into the desktop app's setup or into
  `bin/wizard` in a terminal of their own. You learn about the credentials file only from
  `tui status --json`: whether it exists, whether it is owner-only, and its tunnel ID. Its
  contents stay unread and unprinted. If they paste a key into the chat, tell them to revoke it
  and create a replacement.
- **You register at Read; the user raises levels.** Write and Command are set by the user in a
  frontend. Before they raise one to Command, explain what it allows
  ([operate.md](references/operate.md#access-levels)).
- **Ask before stopping the Runtime.** Stopping ends every running Operation as Uncertain. Name
  `runningOperations` from `tui status --json` when you ask.
- **Change Proxenos only through its commands and frontends.** The state directory belongs to
  the Runtime, and the connector confirmation is the user's own word, given in a frontend.
- **Sudo only on a yes.** Show the exact command, and why, before running anything as root.

## Look before acting

`bin/tui status --json` (under `~/.local/opt/proxenos/`) is the one reading of where things
stand. Run it at the start of every phase and after each step: it never starts or changes
anything. Its `requirements` list what this machine still lacks, each with `words` saying what
to do. When `runtime` is `absent`, `tunnel`, `connector`, `runningOperations` and `workspaces`
are `null`: unknown, not empty. `bin/tui check` prints the requirements as lines and exits
non-zero unless all are met.

If `bin/tui` is missing, start with the install phase. If it fails to run, see
[troubleshoot.md](references/troubleshoot.md#bintui-will-not-run).

The installed tree's `docs/INSTALL.md` matches the installed version and holds every exact
click-path in the OpenAI and ChatGPT screens. Read the click-paths from there when you hand them
to the user; those screens change.

## Phases

Find where the user stands, then read the matching reference and work it to its completion
criterion:

| The user wants | Read |
| --- | --- |
| Proxenos installed, from nothing or over a broken install | [references/install.md](references/install.md) |
| ChatGPT connected to an installed Proxenos | [references/connect.md](references/connect.md) |
| A Java for the portable archive (the install phase sends you here) | [references/java.md](references/java.md) |
| Day-to-day help: Workspaces, levels, start and stop, rotating the key, using it from ChatGPT | [references/operate.md](references/operate.md) |
| Something broken | [references/troubleshoot.md](references/troubleshoot.md) |
| A newer version | [references/upgrade.md](references/upgrade.md) |
| Proxenos removed | [references/uninstall.md](references/uninstall.md) |

A fresh setup runs install, then connect, and it is finished only at connect's completion
criterion.
