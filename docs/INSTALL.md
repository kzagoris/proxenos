# Installing Proxenos

From nothing to ChatGPT answering its first call on this machine. Linux only.

The distribution is one directory tree. Every path below that starts with `bin/` or `docs/` is
inside it, which these instructions put at `~/.local/opt/proxenos/`:

```
bin/runtime                the Runtime: serves ChatGPT, owns the tunnel child
bin/tui                    the terminal frontend; starts the Runtime when it is not running
bin/wizard                 the Platform-dashboard half of first run; writes the credentials file
bin/install-tunnel-client  downloads tunnel-client and verifies it against SHA256SUMS.txt
lib/                       the jars both launchers share
docs/INSTALL.md            this file
docs/systemd/              a systemd --user unit, for a user who disagrees with no autostart
jre/                       linux-x64 archive only: the Java runtime the launchers use
```

`tunnel-client` is **not** in it, and it is not committed to this repository either. Step 4 fetches
the official binary and checks it before anything runs it.

## 1. What the machine needs

- **Linux on x86_64 or arm64**, with Bash, standard coreutils, and `setsid` (usually in util-linux).
- **A normal login session with `XDG_RUNTIME_DIR` set**, for the Runtime’s private sockets.
- **Outbound HTTPS** and a ChatGPT workspace with developer mode and access to OpenAI Platform
  tunnels. Creating a tunnel requires Tunnels Read + Manage; using it requires Read + Use.
  See the [official tunnel guide](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).
- **A Java 26 runtime, unless you use the linux-x64 archive**, which carries its own and ignores
  `JAVA_HOME`. The code is compiled for Java 26 (class file version 70), so an older
  Java refuses it with `UnsupportedClassVersionError … class file version 70.0`. Either install
  one — [Eclipse Temurin 26](https://adoptium.net/temurin/releases/?version=26), or with mise
  `mise use -g java@temurin-26` — and the launchers find it through `JAVA_HOME`, else `java` on
  `PATH`. Check with `java -version`.
- **`curl` or `wget`, `unzip` and `sha256sum`** — only for step 4.
- **`git`**, only if you want the three Git tools. Without it they answer as if there were no
  repository, and everything else works.
- **A terminal** for `bin/tui` and `bin/wizard`, and a browser for the OpenAI dashboards.

## 2. Get the distribution

Each [release](https://github.com/kzagoris/proxenos/releases/latest) publishes two archives:

- `proxenos-<version>-linux-x64.tar.gz`: the tree plus its own Java runtime, for x86_64.
- `proxenos-<version>.tar.gz`: the tree alone, for any architecture, on your Java 26.

Download one together with `SHA256SUMS`, and check it before unpacking:

```sh
sha256sum --check --ignore-missing SHA256SUMS   # must print your archive name followed by OK
```

The archives also carry a build provenance attestation, which proves they were built by this
repository's release workflow rather than only that they match a list published beside them:

```sh
gh attestation verify proxenos-<version>-linux-x64.tar.gz --repo kzagoris/proxenos
```

Or build it from a checkout. The build brings its own JDK 26 toolchain if the machine has none
, so this needs only a JDK to launch Gradle:

```sh
git clone https://github.com/kzagoris/proxenos.git
cd proxenos
./gradlew distTar           # → build/distributions/proxenos-0.0.0-dev.tar.gz
./gradlew bundledDistTar    # → build/distributions/proxenos-0.0.0-dev-linux-x64.tar.gz
```

`./gradlew installDist` lays out the same tree unpacked, in `build/install/proxenos/`,
if you would rather copy a directory than a tarball.

## 3. Unpack it

```sh
mkdir -p ~/.local/opt
tar -xzf proxenos-<version>-linux-x64.tar.gz -C ~/.local/opt
mv ~/.local/opt/proxenos-<version>-linux-x64 ~/.local/opt/proxenos
```

The checkout is not needed to run the installed application.

The tree must stay together: `bin/tui` starts the Runtime it finds at `bin/runtime` beside it. To
type `tui` from anywhere, link the launcher rather than copying it — the launcher follows the
link back to its own tree:

```sh
mkdir -p ~/.local/bin
ln -s ~/.local/opt/proxenos/bin/tui ~/.local/bin/tui
```

## 4. Install tunnel-client, verified

```sh
~/.local/opt/proxenos/bin/install-tunnel-client
```

It downloads the release this build pins — `tunnel-client` **v0.0.14**, from
[openai/tunnel-client](https://github.com/openai/tunnel-client/releases/tag/v0.0.14) — together
with that release's `SHA256SUMS.txt`, and refuses to install unless the archive's SHA-256 matches
its line there. A missing line is a refusal, not a pass. Nothing is written until the check
passes, and it says the hash it checked.

**Where it lands: the state directory's `tools/`**, which is
`$XDG_STATE_HOME/proxenos/tools/tunnel-client`, else
`~/.local/state/proxenos/tools/tunnel-client`. Not `PATH`. The Runtime looks on `PATH`
first and in `tools/` second, and `tools/` is the place that:

- needs no root and no edit to a shell profile;
- is found the same way whether the Runtime was started from a terminal, by `bin/tui`, or by a
  systemd `--user` manager whose `PATH` is short;
- sits beside the rest of the Runtime's per-user state, and goes when it goes.

A `tunnel-client` you already keep on `PATH` still wins, and the installer says so if it sees one.
If you moved the state directory with `PROXENOS_STATE_DIR`, the installer honours it; a
`state_dir` set only in `config.toml` is not read by the installer, so set the variable for that
one run.

**By hand**, if you would rather not run a script that downloads things:

The following block runs in a subshell that stops on any download, checksum, or extraction
failure. It checks exactly the requested archive and installs only after successful extraction.

```bash
(
  set -euo pipefail
  v=0.0.14; a=amd64     # arm64 on an ARM machine
  base=https://github.com/openai/tunnel-client/releases/download/v$v
  archive=tunnel-client-v$v-linux-$a.zip
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  cd "$work"
  curl -fLO "$base/$archive" -fLO "$base/SHA256SUMS.txt"
  awk -v name="$archive" '$2 == name || $2 == "*" name { print }' SHA256SUMS.txt > selected.sha256
  sha256sum --check selected.sha256  # must print your archive name followed by OK
  unzip -p "$archive" tunnel-client > tunnel-client
  state="${PROXENOS_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/proxenos}"
  (umask 077 && mkdir -p "$state/tools")
  staged=$(mktemp "$state/tools/.tunnel-client.XXXXXX")
  trap 'rm -rf "$work"; rm -f "$staged"' EXIT
  install -m 755 tunnel-client "$staged"
  mv -f "$staged" "$state/tools/tunnel-client"
)
```

An absent checksum line fails the check. Other release assets are neither downloaded nor checked.
As with the installer, export `PROXENOS_STATE_DIR` if `config.toml` alone moves your state.

What the check proves: the archive you have is the one the release published. It does not prove
who published the release — `SHA256SUMS.txt` comes from the same place. For v0.0.14 linux-amd64
the recorded hash, measured when this design was tested, is
`15bd17e805cad39d412199115bb9e10a978dd35258a114cdf25dd2ae6681c7d3`.

## 5. Create the tunnel and write the credentials

```sh
~/.local/opt/proxenos/bin/wizard
```

It walks the OpenAI Platform dashboard with you — create a tunnel, associate it with your ChatGPT
workspace, create a runtime key, enable developer mode under **Settings → Security and login** —
and writes the one file the Runtime will not start without,
`$XDG_CONFIG_HOME/proxenos/credentials` (default `~/.config/proxenos/credentials`),
at mode `0600`. The key is read hidden and never echoed.
This happens once; re-run it to rotate the key.

## 6. Register a Workspace

Nothing is reachable until you register a directory. From the TUI, `[n]` registers one; or, with
the Runtime stopped:

```sh
~/.local/opt/proxenos/bin/runtime register ~/code/some-project --name some-project
```

Every Workspace starts at **Read**. Raise it in the TUI when you mean to.

## 7. Start it, and create the connector

```sh
~/.local/opt/proxenos/bin/tui
```

The TUI starts the Runtime if it is not running and opens the workspace list. Use `[n]` to
add a Workspace and `[m]` to manage the selected one. `[a]` opens Activity for this Runtime
run only; previous runs stay stored in the Runtime but are not shown in the dashboard.

`[i]` opens **Review**: one row per link — **Runtime**, **Tunnel**, **Connector** — each with
its own state, and `[Enter]` opens the selected row's detail. On a fresh install the status
line shows **Connector · Unconfirmed**; the Connector detail carries the instructions:
**Plugins → Add → Create MCP App**, set **Connection** to **Tunnel** with your tunnel ID, and
choose **No authentication**. Press `[C]` there once you have
completed setup; this records your confirmation, not a connectivity measurement.
A connection failure and the tunnel's own words are on the Tunnel row and its detail. A stage
below a Runtime that is not running reads **Can't tell** rather than a fault, because there is
nothing to measure.

The Tunnel segment reads **Connected** once the tunnel's poll succeeds. `[Esc]` returns to the
workspace list, and `[q]` closes the dashboard while the Runtime keeps running. Stop the Runtime
explicitly with `[i]`, selecting the Runtime row, `[Enter]`, then `[X]` and `[y]`; `[S]` starts it
again from the Runtime detail or from the workspace list while it is not running.

## 8. The first call

In a ChatGPT conversation with your connector enabled, ask it to list your workspaces. It calls
`list_workspaces`, the call appears in the TUI's Activity, and ChatGPT answers with the Workspace
you registered in step 6.

## After a reboot

Nothing starts by itself, deliberately: a Runtime started at login would leave a Workspace at
Command reachable from ChatGPT with nobody present. Until you start it once —
`bin/tui`, or `bin/runtime` — ChatGPT's calls fail, and the failure shows up there as tool errors
rather than anywhere on this machine.

If you have read that and disagree, `docs/systemd/proxenos.service` is a ready
systemd `--user` unit, with the reason and the steps in its header. The product never installs it
and never offers to.

## Upgrading and removing

Upgrade by replacing the tree in `~/.local/opt/proxenos/` with a new one; your
Workspaces, Activity and credentials live outside it and stay. If the new version's tool catalog
differs, the setup confirmation notice comes back, because the connector in ChatGPT is still serving
the old one.

To remove everything: stop the Runtime, delete `~/.local/opt/proxenos/`,
`~/.local/state/proxenos/` and `~/.config/proxenos/`, revoke the runtime key in the
Platform dashboard, and delete the connector in ChatGPT.
