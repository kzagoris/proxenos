# Operate

Day-to-day requests, each its own small task. `bin/` is `~/.local/opt/proxenos/bin/`.
`bin/tui status --json` before and after each one shows that it worked.

## Start, stop, reboot

- Nothing starts at login. That is deliberate: a Runtime started unattended would leave a
  Workspace at Command reachable with nobody present. After a reboot, run `bin/tui start`, or
  the user opens the GUI or the TUI.
- `docs/systemd/proxenos.service` is a systemd `--user` unit for a user who has read that
  reason, in its header, and still wants autostart. Install it only when they ask, after showing
  them the header.
- `bin/tui stop` follows the SKILL.md rule: name `runningOperations` and get a yes first.
- Closing the GUI or quitting the TUI leaves the Runtime running. That is not a fault.

## Workspaces

- Register at Read with `bin/tui register <dir> --name <name>` while the Runtime runs, or
  `bin/runtime register <dir> --name <name>` while it is stopped. A Workspace registered while
  stopped is served from the next start.
- Renaming, moving to another Root and forgetting are done in a frontend: the GUI's
  **Registration** tab, or the TUI's `m` then `e`. Moving puts the Workspace back at Read.
- **Broken** means the Root no longer resolves to the directory it was registered against. It is
  absent from discovery until the user checks the directory and re-confirms it in a frontend
  (**Re-confirm at Read**).

## Access levels

| Level | ChatGPT may |
| --- | --- |
| None | nothing; the Workspace is withheld, as though it did not exist |
| Read | list, read, search, and see Git status, diffs and history |
| Write | Read, plus create and edit files |
| Command | Write, plus run programs (`run_command`) and collect their results (`get_result`) |

The user sets the level in the GUI (**Workspaces →** select → **Access**) or the TUI (`m`, then
`0`–`3`). Command asks them to confirm. Before they raise it, tell them:
- Commands run as their Linux account, with everything it can reach.
- The Root is only the starting directory.
- Once Command is on, the Runtime runs each command without asking. ChatGPT's own approval
  prompt is the remaining check.

Lowering a level stops nothing already running; stop that work from Activity.

## Rotating the runtime key

1. The user creates a replacement key for the same tunnel in Platform; it never shows an old
   one again.
2. Stop the Runtime, with consent (SKILL.md).
3. The user saves the new key: **Change credentials** then **Save & connect** in the GUI's
   setup, or `bin/wizard` in their own terminal.
4. `bin/tui start` if the GUI didn't start it, then wait for `tunnel.state` `Connected`.
5. The user revokes the old key in Platform.

## Using it from ChatGPT

The README's [How to use it from ChatGPT](https://github.com/kzagoris/proxenos#how-to-use-it-from-chatgpt)
has requests to paste. What helps most:

- Name the Workspace in the request, and ask for the tool when ChatGPT only describes a command
  instead of running it: "Use Proxenos's `run_command` in `my-project` to run `npm test`."
- A command that outlasts its call returns a **Handle**. Ask ChatGPT to collect it with
  `get_result` rather than start the command again.
- **Try…** on a Workspace's **Tools** tab runs any tool through the same path ChatGPT takes, so a
  user can see what ChatGPT will get without ChatGPT.
- Activity, in the GUI or the TUI's `a`, shows every Operation of the current Runtime run as it
  happens, and stops a running command.
