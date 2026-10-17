# Connect ChatGPT

**Done when** `bin/tui await-listing <workspace>` exits 0: ChatGPT itself listed that
Workspace's Root, successfully, after the wait began. Nothing short of that proves the whole
path works: not a Connected tunnel, not the user saying the plugin exists, not a frontend's Try.
Below, `bin/` means `~/.local/opt/proxenos/bin/`.

The user needs:
- a ChatGPT account whose workspace allows developer mode and custom MCP servers;
- access to OpenAI Platform tunnels: **Tunnels Read + Manage** to create one, **Read + Use** to
  use it.

If they lack any of this, an administrator has to grant it. Say so before they spend time in
the dashboards.

## Choose the route

- **Desktop setup**, on the linux-x64 archive with a display. Start it with
  `setsid -f bin/gui >/dev/null 2>&1`, or the user opens **Proxenos** from the menu. Its wizard
  covers the Tunnel, Workspace and ChatGPT steps below, with illustrated guides, and saves the
  credentials itself. Your part is to watch `tui status --json` and answer questions.
- **Terminal setup**, everywhere else. The user runs `bin/wizard` in a terminal of their own: it
  reads the key hidden and refuses to run without a terminal. Its closing lines suggest opening
  `bin/tui`; tell the user you will start the Runtime instead, then do steps 2–5 yourself.

Read the exact screen names from the installed `docs/INSTALL.md` (§5 for the tunnel and key, §7
for the plugin) when you hand them to the user.

## 1. Tunnel and runtime key (the user's hand-off)

In OpenAI Platform, the user:
1. Creates a tunnel and associates it with the ChatGPT workspace they will use.
2. Creates a **Restricted** runtime key, with Tunnels **Read + Use**. Platform shows a key only
   once; a lost key means a replacement for the same tunnel.
3. Enters the tunnel ID and key in the desktop setup's Tunnel step (**Save & connect**) or in
   `bin/wizard`.

Wait until the `credentials` requirement shows `met: true`. If its `words` say other users can
read the file, ask the user before you `chmod 600` it.

## 2. Runtime and Tunnel

The desktop setup starts the Runtime on **Save & connect**. On the terminal route, run
`bin/tui start`. Then poll `tui status --json` every few seconds until `tunnel.state` is
`Connected`, which normally takes under a minute. You can do step 3 meanwhile.
- If it is still `Connecting` after a minute, or carries a `complaint`, or turns `Failed`, go to
  [troubleshoot.md](troubleshoot.md#tunnel).
- `Connected` means the link to OpenAI is up. It says nothing yet about ChatGPT.

## 3. A Workspace at Read

The desktop setup's Workspace step registers one. Otherwise ask the user which directory ChatGPT
should see first; suggest a project rather than their home directory. Name it after the
directory, short and lowercase, since the user will type the name to ChatGPT. Then:

```sh
bin/tui register /absolute/path --name short-name
```

It refuses a Root that overlaps one already registered, and registers nothing. Relay its words.
If the user wants the overlap anyway, they register it in a frontend.

## 4. The plugin in ChatGPT (the user's hand-off)

Give the user the click-path from `docs/INSTALL.md` §7, with these values:
- **ChatGPT on the web**, not the desktop or mobile apps.
- **Connection: Tunnel**, with `tunnelId` from `tui status --json`.
- **Authentication: No authentication.** The form defaults to OAuth, which fails before any tool
  call; the runtime key in the tunnel is the authentication.
- Icon: `~/.local/opt/proxenos/share/icons/proxenos.png`.

Ask them to confirm in a frontend once the plugin is created and connected. The GUI's label is
**I updated the connector**, under Connection → Connector. In the TUI it is `i`, the
Connector row, `Enter`, `C`. This turns `connector` to `Confirmed`. It records their word, and you leave it to them.

## 5. The first request

`await-listing` prints the request the user must send, then blocks until ChatGPT makes it.
Relay the request before the wait ends. Do either of these:
- Run it as a background job and read its output while it waits.
- Or run `bin/tui await-listing short-name --timeout 1` once to get the request, relay it
  word for word, then wait with `--timeout 300`.

The user sends it in a new ChatGPT conversation, with Proxenos picked from `@`. The exit status
says what happened:
- **0**: ChatGPT's listing arrived, and setup is complete.
- **2**: the time ran out and nothing is wrong. Wait again for as long as the user is still
  working in ChatGPT.
- **1**: waiting cannot succeed (no such Workspace, at None, Broken, moved, or no Runtime), and
  the output says which. Fix that first.

When it exits 0, tell the user what now works: ChatGPT can read in that Workspace. Raising it to
Write or Command is their choice, made in a frontend ([operate.md](operate.md#access-levels)).
