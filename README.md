# Proxenos

[![M8ven Score](https://m8ven.ai/badge/mcp/kzagoris/proxenos)](https://m8ven.ai/mcp/kzagoris/proxenos?s=readme)

*Proxenos for ChatGPT.* In ancient Greece a *proxenos* was a citizen who hosted and acted for a
foreign city's envoys; this one hosts ChatGPT on your machine.

Connect ChatGPT to selected directories on your Linux machine. Proxenos lets ChatGPT read files, search code, inspect Git changes, edit files, and run commands according to the access level you choose for each workspace.

A desktop app and a terminal dashboard manage your workspaces and show what ChatGPT does in them. A separate Runtime answers tool calls through the **OpenAI Secure MCP Tunnel** and continues running when you close either one.

https://github.com/user-attachments/assets/53cd51ca-2e73-4c89-89e5-fea6162e4fff

```text
ChatGPT → OpenAI Secure MCP Tunnel → local Runtime → your workspaces
                                          ↑
                                desktop app · terminal dashboard
```

The Runtime uses local Unix sockets and manages the tunnel client itself. No public HTTP listener is needed on your machine.

## What you can do

- Register several directories, each with its own name and access level.
- Let ChatGPT browse files, search contents, and inspect Git status, diffs, and history.
- Enable file creation and editing for selected workspaces.
- Import ChatGPT PNG/PDF attachments at Write and export exact local PNG/PDF bytes at Read, up to 1 MiB. See [attachment transfer](docs/ATTACHMENTS.md).
- Enable commands for builds, tests, and other local work.
- Watch Activity live, follow a running command's output as it prints, and stop it.
- Try any tool yourself, through the same path ChatGPT's calls take, before ChatGPT does.
- Retrieve results from commands that outlast their initial tool call.

## The desktop app

The desktop app (`bin/gui`) puts everything the Runtime exposes in one window, with three
destinations in its sidebar:

- **Workspaces** lists every registered directory with its access level. Select one to set
  its level under **Access**, see under **Tools** exactly which tools ChatGPT is served against it
  and **Try…** any of them, or rename, move or forget it under **Registration**.
- **Activity** shows the calls of the current Runtime run as they happen. Running commands sit at
  the top with their latest output; select one to read what `get_result` would return now, or
  **Stop command…**. Entries that need your attention are flagged.
- **Connection** has one row each for the **Runtime**, the **Tunnel** and the ChatGPT
  **Connector**, with what each reading means and what to do about it: start or stop the
  Runtime, connect or disconnect the tunnel, and the steps for creating the connector in ChatGPT.

![Workspaces: three registered directories at Command, Write and Read, with the Tools tab listing each tool and its Try button](docs/images/gui-workspaces.png)

![Activity: ChatGPT's tool calls in a list, and a running npm test streaming its output](docs/images/gui-activity.png)

The status line at the top shows all three stages from every view. Raising a workspace to
Command asks for confirmation first. The window follows your desktop's light or dark setting
while it is open, and `Ctrl+1`, `Ctrl+2` and `Ctrl+3` switch views, `Ctrl+N` adds a workspace,
`Esc` goes back and `Ctrl+W` closes the window. Closing it leaves the Runtime running.

The terminal dashboard (`bin/tui`) offers the same management from a terminal and works on any
architecture. Both attach to the same Runtime, and you can use them side by side.

## Requirements

- **Linux**, with a terminal, Bash, Git, and `setsid` (usually supplied by util-linux).
- **Java 26 or newer** to run the portable archive or to build; the linux-x64 archive bundles its own. The repository includes the Gradle wrapper; the tool versions are recorded in [mise.toml](mise.toml).
- The official **`tunnel-client`** executable, available from [OpenAI Platform tunnel settings](https://platform.openai.com/settings/organization/tunnels) or the [official releases](https://github.com/openai/tunnel-client/releases/latest). On linux-x64, choose **Install tunnel-client** in the desktop setup wizard; from a terminal, use the distribution’s `bin/install-tunnel-client`. Both use the same verified installer before placing it in the state directory’s `tools/`. See [installation instructions](docs/INSTALL.md).
- A ChatGPT account/workspace with **developer mode** available, plus access to OpenAI Platform tunnels. Availability depends on your account and workspace policy.
- A normal Linux login session with `XDG_RUNTIME_DIR` set, and outbound HTTPS access to OpenAI.
- For the desktop app: x86_64, and X11 or XWayland with libGL, libX11 and fontconfig.

Creating tunnels requires **Tunnels Read + Manage**; using them requires **Tunnels Read + Use**. Your tunnel must be associated with the ChatGPT workspace you will use. See the [official Secure MCP Tunnel guide](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) for access requirements and downloads.

## Set it up with a coding agent

If you use Claude Code, Codex or another agent that reads skills, add the Proxenos skill and ask
it to set Proxenos up:

```bash
npx skills add kzagoris/proxenos
```

The agent downloads and checks the release, installs `tunnel-client` and, on the portable
archive, a suitable Java. It checks progress with `bin/tui check` and `bin/tui status --json`.
It hands you the steps only you can do: creating the tunnel and runtime key in OpenAI Platform,
typing the key into the desktop setup or `bin/wizard`, and creating the plugin in ChatGPT. Then
it waits with `bin/tui await-listing` for ChatGPT's first real request. It never sees your
runtime key and never raises a Workspace above Read. The same skill covers upgrading,
troubleshooting and removing Proxenos.

## 1. Get the application

Download a release from [GitHub Releases](https://github.com/kzagoris/proxenos/releases/latest):

- **`proxenos-<version>-linux-x64.tar.gz`** carries the Runtime, TUI, GUI and Java runtime.
- **`proxenos-<version>.tar.gz`** carries the Runtime and TUI on any Linux architecture and
  needs Java 26 or newer.

```bash
mkdir -p ~/.local/opt
tar -xzf proxenos-<version>-linux-x64.tar.gz -C ~/.local/opt
cd ~/.local/opt/proxenos-<version>-linux-x64
```

The tree holds the launchers, the setup wizard, the verified tunnel installer, generated
third-party notices, and the installation guide. Keep it together: each frontend finds the
Runtime beside it automatically.
Run the commands below from this directory. Each release publishes `SHA256SUMS` and a build
provenance attestation; [the installation guide](docs/INSTALL.md) shows how to check both.

To build from source instead, `./gradlew installDist` lays out a development tree with the GUI
in `build/install/proxenos/`. It uses your installed Java 26 or newer; the linux-x64 archive adds `jre/`.

To put the desktop app in your application menu, run the optional installer from the linux-x64
tree:

```bash
./bin/install-desktop-entry
```

Re-run it after moving the tree, or pass `--remove` to remove the entry and icon. The desktop app
needs x86_64, X11 or XWayland, libGL, libX11 and fontconfig; native Wayland without XWayland is
unsupported. Startup diagnostics are appended to `$XDG_RUNTIME_DIR/proxenos/gui.log`. If it
opens at the wrong size, set `gui_scale` as the [installation guide](docs/INSTALL.md) describes.

## 2. Follow the desktop setup wizard

Open **Proxenos** from your application menu, or run `./bin/gui`. On a new install it opens a
setup wizard that takes you from creating a tunnel to ChatGPT's first successful request, in
five steps:

1. **Welcome** checks local requirements and offers **Install tunnel-client** if it is missing.
   Click it to download and verify the pinned release; progress and failures appear here, and a
   successful install rechecks requirements automatically. Choose a new tunnel or one you already have.
2. **Tunnel** walks you through creating the tunnel in OpenAI Platform, associating it with your
   ChatGPT workspace, and entering its tunnel ID and runtime key. Expand the illustrated guides
   when you cannot find a setting. **Save & connect** saves the credentials and starts the Runtime.
3. **Workspace** registers a directory at **Read**, or lets you choose a workspace you have
   already registered.
4. **ChatGPT** guides creation in **ChatGPT on the web**: open **Plugins from the main sidebar**,
   then **Add → Add custom MCP server**. Set **Connection · Tunnel**, enter the same tunnel ID
   from the tunnel you created in OpenAI Platform, and choose **No authentication**.
   Create and connect the plugin in ChatGPT, then confirm here.
5. **Try it** waits until ChatGPT lists the workspace's root with `list_directory`, then shows
   the Operation it received and when. Your confirmation in step 4 does not count; only a real,
   successful request from ChatGPT does.

![Welcome step: how this machine, the tunnel and ChatGPT connect, a local requirements check, and the choice between a new and an existing tunnel](docs/images/gui-setup-welcome.png)

![Tunnel step: links to Platform tunnel settings, expandable creation and tunnel ID guides, and the tunnel ID field](docs/images/gui-setup-tunnel.png)

![ChatGPT step: web-only instructions, Plugins in the main sidebar, and the tunnel ID from the tunnel created in OpenAI Platform](docs/images/gui-setup-chatgpt.png)

You can leave at any point: **Finish later** returns to the main window, and **Continue setup**
in **Connection** picks up where you left off. Closing the window keeps your completed steps and
leaves the Runtime running, but it discards a runtime key you have not saved yet. Installation
requires your explicit click, needs no root or `PATH` changes, and does not start the Runtime.
An existing executable on `PATH` takes priority. If installation fails, review its details,
correct the problem, and choose **Install tunnel-client** again. For the terminal flow, run
`./bin/install-tunnel-client` before `./bin/wizard`.

The credentials are saved to `${XDG_CONFIG_HOME:-~/.config}/proxenos/credentials` with mode
`0600`, so only your user can read or write them. To replace them while the Runtime is running,
**Stop Runtime…** first; stopping it ends any running Operations.

### Terminal setup

For the portable archive or a terminal workflow, run the interactive terminal wizard:

```bash
./bin/wizard
```

It walks you through creating a tunnel in OpenAI Platform, associating it with your ChatGPT workspace, obtaining a runtime API key, and enabling ChatGPT developer mode. Paste the tunnel ID and key into the wizard when prompted; key input is hidden.

The wizard saves these values in:

```text
${XDG_CONFIG_HOME:-~/.config}/proxenos/credentials
```

The file contains `TUNNEL_ID` and `RUNTIME_KEY` and is written with mode `0600` (readable and writable only by its owner). The Runtime refuses credentials with broader permissions. Keep this file outside the repository. The wizard supplies the credentials; the verified installer in step 1 supplies `tunnel-client`.

## 3. Manage your workspaces

If you completed desktop setup, your first workspace is already registered. Open **Workspaces**
to manage it or add another. If you used the terminal wizard, open the desktop app or TUI and
register your first workspace as follows.

Open **Proxenos** from your application menu, or run:

```bash
./bin/gui
```

With saved credentials, the desktop app starts the Runtime if necessary, then attaches to it.

1. Select **Add Workspace** (`Ctrl+N`).
2. Choose its root directory, or type the path, and give it a name such as `my-project`.
3. Leave it at **Read** for your first connection test.
4. Wait for **Tunnel** in the status line to show **Connected**.

From a terminal, or with the portable archive, run `./bin/tui` instead: press **`n`** to
register a directory, enter its root and a name, and wait for **Connected**.

Connected means the Runtime has a working link to the tunnel. Verify the full path with a ChatGPT tool call in the next section.

You can also register a workspace before starting the Runtime:

```bash
./bin/runtime register /absolute/path/to/project --name my-project
```

This command registers it at Read and requires the Runtime to be stopped. Use the desktop app or the dashboard to register directories while the Runtime is running.

## 4. Create the connection in ChatGPT on the web

Desktop setup includes this handoff and the first-request check. These are also the steps to
follow after terminal setup, or when you need to create the connection again.

**Use ChatGPT on the web in your browser at [chatgpt.com](https://chatgpt.com/).** Open
**Plugins from the main sidebar**. **Settings → Plugins** manages installed plugins; it is
not the page for creating this connection. Keep the Runtime running throughout these steps.

1. Open **Plugins from the main sidebar**. If your account requires developer mode for custom MCP access, enable it in settings; a workspace administrator may need to grant access first.
2. Select **Add → Add custom MCP server** to open the connection form.
3. Give it a name such as **Proxenos** and a description such as “Read and work with my registered local workspaces.” For the plugin's icon, upload **`share/icons/proxenos.png`** from the unpacked Proxenos archive. Both archives include this image.
4. Set **Connection → Tunnel**. Enter the ID of the tunnel you created in OpenAI Platform: **the same `tunnel_...` ID you saved in Proxenos during tunnel setup**. Use **Copy tunnel ID** in the Proxenos setup screen to copy that value.
5. Set **Authentication** to **No authentication**. The dialog defaults to OAuth, which fails before any tool call. This application's MCP endpoint does not implement OAuth; the tunnel uses the runtime key you saved locally.
6. Review and accept ChatGPT's warning, create the plugin (**Create as a plugin** in the documented web flow), and review the discovered tools. When the connection dialog appears, click **Connect Proxenos** (or **Connect** followed by the name you chose).
7. Start a new conversation, type **`@`**, and pick **Proxenos** from the **Plugins** list.

With ChatGPT's default permission, **Allow low-risk tools**, read-only calls such as `git_status` run at once, while calls that change files or run commands may ask for approval first. **See details** in that prompt shows the exact arguments, including the command.

The web navigation and connection dialog were checked against screenshots on October 14, 2026. OpenAI's
[connection guide](https://developers.openai.com/plugins/deploy/connect-chatgpt) and
[Secure MCP Tunnel guide](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
describe the **Connection → Tunnel** form used here. UI labels and tunnel availability can
vary by account; these instructions are for the web Plugins page.

Ask ChatGPT:

> Use Proxenos to list my available workspaces. Then list the files at the root of my-project and summarize its README if one exists.

Confirm that ChatGPT receives the expected workspace and files, and that the calls appear in Activity.

If the status line shows **Connector · Unconfirmed**, open **Connection**, select **Connector**,
and select **I updated the connector** once you have created or refreshed the connection. In the
dashboard, open **`i`** (Review), select the **Connector** row, press **`Enter`**, then uppercase
**`C`**. This acknowledgement records your word; it does not test ChatGPT connectivity.

![Connection: the Runtime, Tunnel and Connector rows, with the Connector's setup steps for ChatGPT](docs/images/gui-connection.png)

## Access levels

Each workspace has one access level. Higher levels include the abilities below them.

| Level | What ChatGPT can do |
| --- | --- |
| **None** | Nothing. The workspace is hidden from discovery. |
| **Read** | List and read files, search, and inspect Git status, diffs, and history. |
| **Write** | Everything in Read, plus create, replace, and edit files. |
| **Command** | Everything in Write, plus run programs and collect their results. |

In the desktop app, select a workspace and choose its level under **Access**. **Tools** shows
which tools that level allows, and **Registration** renames it, moves it to another folder (back
at Read) or forgets it. An unavailable workspace offers **Re-confirm at Read** once you have
checked its directory.

In the dashboard, select a workspace with **↑/↓** and press **`m`** (Manage). There, **`0`–`3`**
set None, Read, Write, or Command; **`e`** renames it; **`w`** opens Tools. An unavailable
workspace has a Review action explaining how to re-confirm its directory. From Workspaces,
**`a`** opens Activity; select a running command there and press **`s`** to stop it. **`i`**
opens Runtime, with **`d`** to connect/disconnect and **`X`** to stop the Runtime after
confirmation. **Esc** goes back; **`q`** closes only the dashboard. Use **Page Up/Down** to
scroll long details.

Both show Activity for the current Runtime run only. Previous-run records remain in the Runtime
but never appear in either frontend.

**Command runs with the full authority of your Linux account.** The workspace root is the starting directory, not a sandbox: commands can access other files and resources your account can reach. Once enabled, the Runtime does not prompt for each command. Both frontends ask for confirmation when you raise a workspace to Command.

Closing the desktop app or the dashboard leaves the Runtime and its access levels in place. Lowering a level or disconnecting the tunnel does not stop work already running. Stop the command from Activity, or stop the Runtime, to end running work.

## How to use it from ChatGPT

Write your request in ChatGPT's normal message textbox and send it with the Proxenos connection enabled, as described in [the connection setup](#4-create-the-connection-in-chatgpt). Include the workspace name so ChatGPT knows where to work. You can use ordinary language or include an exact shell command; no JSON or special command prefix is needed.

### Run your first command

1. Open the desktop app and keep the Runtime running and Connected.
2. Select your registered workspace, choose **Command** under **Access**, and confirm with **Raise to Command**. In the dashboard, open **`m`** (Manage), press **`3`**, then **`y`**. This grants commands the authority described above.
3. In your ChatGPT conversation with Proxenos enabled, paste the following into the message textbox. Replace `my-project` with the name you registered:

   > Use Proxenos to run `pwd` in the workspace `my-project`. Execute it with the `run_command` tool and show me the actual output.

4. Send the message. ChatGPT should call `run_command`; the command runs on the Linux machine hosting the Runtime, starting in that workspace's root directory.
5. Check that the returned path matches your workspace and that the command appears in Activity.

To check a workspace without ChatGPT, use **Try…** on its **Tools** tab: the call goes through the same path ChatGPT's calls take, and appears in Activity too.

Typing a command into the textbox asks ChatGPT to execute it through the connected tool. If ChatGPT only explains the command or prints a code block, follow up with:

> Execute that command now using Proxenos's `run_command` tool in `my-project`, and return its output.

### Commands you can paste into the textbox

Show files, including hidden entries:

> Use Proxenos to run `ls -la` in `my-project` and show the output.

Run tests in a workspace containing this repository:

> Use Proxenos to run `./gradlew test` in `my-project`. Report the exit code and summarize any failures.

Run a command in a subdirectory (replace `backend` with an existing directory relative to the workspace root):

> Use Proxenos to run `pwd` in `my-project`, with the working directory set to `backend`. Show the output.

Commands run through `/bin/sh -c` and use the programs and environment available to the Runtime. Choose commands appropriate for your project; for example, `npm test` requires Node.js and a project with that test script.

### Collect a long-running command's result

If a command outlasts the initial tool call, it continues running and returns a **handle**. Ask ChatGPT to collect its output:

> Use Proxenos's `get_result` tool to collect the result for the handle returned by the previous command in `my-project`. If it is still running, show the output so far.

Collect the same handle again for further updates instead of starting the command again. Keep the workspace at Command while collecting results. Meanwhile, the desktop app's **Activity** shows the command under **Running now** with its latest output; select it to see what `get_result` would return now. To stop it, select **Stop command…** and confirm, or in the dashboard open **`a`** (Activity), select it, and press **`s`**.

### Read and edit files

At **Read** or higher:

> Show the uncommitted changes in my-project and explain what they do.

At **Write** or higher:

> In my-project, update the introduction in README.md to describe the current application.

## Available tools

| Access required | Tools |
| --- | --- |
| Discovery | `list_workspaces` |
| Read | `list_directory`, `read_file`, `read_files`, `search`, `git_status`, `git_diff`, `git_log` |
| Write | `write_file`, `edit_file`, `create_directory` |
| Command | `run_command`, `get_result` |

`read_files` reads up to 20 ranges in one bounded reply, with independent results in input order.
Use it for several small files or explicit review ranges. For one large complete file, prefer
`read_file`, which has more room per file. With either tool, follow `nextOffset` and
`nextByteOffset` as the next call's `offset` and `byte_offset` until no continuation remains.
For a batch, retry `not_processed` ranges unchanged and continue each `ok` item independently;
keep the original ending line when calculating the next limit for an explicit range. The 64 KiB cap
includes both readable text and structured data, so source bytes alone do not predict reply size.
See [complete-file measurements](docs/batch-read-efficiency.md) for the tradeoffs.

`search` supports filename/content modes, include/exclude globs, small context windows, and a hit limit.
Directory listings return consecutive pages using `after`; `git_diff` accepts literal paths and patch,
stat, or name-only output. Incomplete replies say how to continue or narrow the selection.

At Write, `create_directory` creates missing parents only with `parents=true`. `edit_file` accepts
either one exact replacement or up to 100 non-overlapping replacements matched against one original
version; `preview=true` returns a bounded diff without replacement. Apply checks the current bytes
again. Mutations and preview require `request_id` so repeated Deliveries return the original answer.
Reads and directory pages are not snapshots; concurrent changes can affect continuation.
In the frontends' Try forms, enter array arguments as JSON, for example
`files=[{"path":"README.md","offset":1,"limit":20}]` or
`edits=[{"old_text":"before","new_text":"after"}]` (enter the array value in the named field).

Workspace operations name their target workspace explicitly. Adding a workspace or changing its level takes effect in the Runtime without adding new tool names.

## Start, stop, and configuration

In the desktop app, **Connection → Runtime** has **Stop Runtime…** and, while it is stopped, **Start Runtime**; **Connection → Tunnel** connects or disconnects the tunnel. From a terminal, start the Runtime without opening a frontend, inspect one snapshot, or stop it:

```bash
./bin/tui start
./bin/tui --dump --frames 1
./bin/tui stop
```

For scripts and coding agents, `./bin/tui check`, `./bin/tui status --json`,
`./bin/tui register <directory> --name <name>` and `./bin/tui await-listing <workspace>` never
start the Runtime; see [the installation guide](docs/INSTALL.md#checking-from-a-terminal-or-from-a-coding-agent).

There is **no automatic startup after reboot**. Open the desktop app or the dashboard, or run `tui start` again. Stopping the Runtime preserves workspace registrations and ends running operations; their effects may be uncertain and are not rolled back.

Default locations:

| Data | Location |
| --- | --- |
| Credentials | `$XDG_CONFIG_HOME/proxenos/credentials` (fallback: `~/.config/proxenos/credentials`) |
| Optional configuration | `$XDG_CONFIG_HOME/proxenos/config.toml` (fallback: `~/.config/proxenos/config.toml`) |
| Persistent state | `$XDG_STATE_HOME/proxenos` (fallback: `~/.local/state/proxenos`) |
| Runtime sockets | `$XDG_RUNTIME_DIR/proxenos/` |
| Runtime output when started by a frontend | `runtime.log` beside the control socket |
| Desktop app startup diagnostics | `$XDG_RUNTIME_DIR/proxenos/gui.log` |

Defaults are sufficient for normal use. For example, to use a tunnel executable outside `PATH`, add this to `config.toml`, substituting its actual absolute path:

```toml
tunnel_client = "/absolute/path/to/tunnel-client"
```

The Runtime also checks `tools/tunnel-client` inside its state directory. Credentials belong only in the credentials file. See [Runtime configuration](runtime/src/main/kotlin/io/github/kzagoris/proxenos/runtime/Configuration.kt) for supported settings and environment overrides. The frontends find the control socket exactly as the Runtime does, `control_socket` in TOML included; the dashboard's `--control-socket` overrides both.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Runtime cannot find its launcher | Keep `bin/` and `lib/` together in the distribution; for a custom layout, set `PROXENOS_RUNTIME` to the Runtime launcher. |
| Missing credentials or unsafe permissions | Run `./bin/wizard`; the credentials file must be owner-only (`chmod 600` on that file). |
| `UnsupportedClassVersionError … class file version 70.0` | The portable archive found a Java older than 26. Install a newer one, or link it into the tree as `jre/` ([installation guide](docs/INSTALL.md#3-unpack-it)). |
| `tunnel-client` cannot be found | Install the executable on `PATH`, in the state directory's `tools/`, or set `tunnel_client` in TOML. |
| The desktop app does not open | Read `$XDG_RUNTIME_DIR/proxenos/gui.log`. It needs X11 or XWayland (`DISPLAY` set), libGL, libX11 and fontconfig, and ships only in the linux-x64 archive. |
| The desktop app is too large or too small | Set `gui_scale` in `config.toml`, for example `gui_scale = 1`, and reopen it. See [the installation guide](docs/INSTALL.md). |
| `XDG_RUNTIME_DIR` is missing | Start from a Linux login session, or configure explicit socket paths; pass the matching control socket to the dashboard. |
| Tunnel is absent in ChatGPT | Check its ChatGPT workspace association and your Tunnels Read + Use access. |
| Connection fails during OAuth | Recreate the connection with **No authentication**. |
| Runtime remains Connecting or becomes Failed | Read the tunnel's complaint under **Connection → Tunnel** or in the dashboard's Review; check the tunnel ID, runtime key, and outbound connectivity. |
| No workspaces are listed | Register a directory and set it to Read or higher. A workspace marked Broken needs its root checked and re-confirmed. |
| A write or command is refused | Check the selected workspace's access level. |
| The status line shows **Connector · Unconfirmed** after an upgrade | In ChatGPT, open the plugin's **Manage** page and choose **Refresh tools**. Review the tools and test in a new conversation, then select **I updated the connector** under **Connection → Connector** (dashboard: `i`, the Connector row, `Enter`, `C`). Recreate the connection if refresh fails. |

**Refresh tools** was verified through the Secure MCP Tunnel with a changed catalog; existing conversations may retain their previous tool list, so test in a new one. Recreating the connection remains the fallback. See [ADR 0014](docs/adr/0014-refresh-the-connector-catalog-explicitly.md) and [OpenAI's refresh instructions](https://developers.openai.com/plugins/deploy/connect-chatgpt#refresh-metadata).

## Development

The project uses Kotlin/JVM, Ktor, the MCP Kotlin SDK, Mosaic for the TUI and Compose Desktop
for the GUI. Its eight modules separate the core behavior, MCP endpoint, local control
interface, Runtime, shared frontend behavior, and the two frontends.

From the repository root:

```bash
./gradlew build
./gradlew runGui    # installs the development tree and opens its desktop app
```

Further reading: [domain terminology](CONTEXT.md), and [architecture decisions](docs/adr/).

## Security

Report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE).

Proxenos is an independent project. It is not affiliated with, endorsed by, or sponsored by
OpenAI. ChatGPT and OpenAI are trademarks of OpenAI.
