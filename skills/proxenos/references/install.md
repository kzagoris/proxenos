# Install

**Done when** `~/.local/opt/proxenos/bin/tui check` reports `runtime` and `tunnel-client` as
`ok`. `credentials` stays missing until the connect phase.

If `~/.local/opt/proxenos` already exists, run its `bin/tui status --json` first. A working
install that is merely old is an upgrade ([upgrade.md](upgrade.md)), not a reinstall.

## 1. Read the machine

```sh
uname -m                                   # x86_64 or aarch64
. /etc/os-release; echo "$ID $VERSION_ID"  # the distribution, for java.md
echo "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR DISPLAY=$DISPLAY WAYLAND_DISPLAY=$WAYLAND_DISPLAY"
java -version 2>&1 | head -1               # only matters for the portable archive
command -v curl unzip sha256sum gh        # gh is optional
```

- `curl`, `unzip` and `sha256sum` are needed (step 4's installer accepts `wget` for `curl`).
  Offer to install whichever is missing.
- `XDG_RUNTIME_DIR` must be set: the Runtime keeps its sockets there. If it is empty, the user is
  not in a normal login session (a bare `su`, some containers). Tell them, and stop until they
  are.
- **Choose the archive.** On x86_64, use `proxenos-<version>-linux-x64.tar.gz`. It carries its
  own Java and the desktop app, which needs X11 or XWayland (`DISPLAY` set), libGL, libX11 and
  fontconfig. Anywhere else, use `proxenos-<version>.tar.gz`: the Runtime and TUI on a Java 26 or
  newer, which [java.md](java.md) installs.

## 2. Download and verify

Work in a fresh empty directory, outside any checkout. Run steps 2 and 3 in one shell
invocation, or set `work` and `archive` again, since they are plain shell variables:

```sh
work=$(mktemp -d) && cd "$work"
tag=$(curl -fsSL https://api.github.com/repos/kzagoris/proxenos/releases/latest | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
version=${tag#v}
archive=proxenos-$version-linux-x64.tar.gz      # or proxenos-$version.tar.gz
curl -fLO "https://github.com/kzagoris/proxenos/releases/download/$tag/$archive" \
     -fLO "https://github.com/kzagoris/proxenos/releases/download/$tag/SHA256SUMS"
sha256sum --check --ignore-missing SHA256SUMS   # must print "<archive>: OK"
```

If `sha256sum` prints anything but OK for the archive, stop: delete the download and tell the
user. Only an archive that passed its check gets unpacked.

Then the provenance check, `gh attestation verify "$archive" --repo kzagoris/proxenos`, which
proves the archive was built by the project's release workflow:
- It verifies: say so.
- `gh` is missing or not logged in (`gh auth login` is what it asks for): the check could not
  run. Tell the user it was skipped and why, then continue.
- It ran and reported a mismatch: stop, exactly as for a failed checksum.

## 3. Unpack

```sh
mkdir -p ~/.local/opt
tar -xzf "$archive" -C ~/.local/opt
mv ~/.local/opt/"${archive%.tar.gz}" ~/.local/opt/proxenos
```

Keep the tree whole: each launcher finds the Runtime beside it. `cat ~/.local/opt/proxenos/VERSION`
names the version installed. Delete `$work`. On the portable archive, now follow
[java.md](java.md).

## 4. Install tunnel-client

```sh
~/.local/opt/proxenos/bin/install-tunnel-client
```

It downloads the `tunnel-client` release this version pins, refuses unless its SHA-256 matches
the release's checksum list, and installs it under the state directory's `tools/`. It changes no
`PATH` and needs no root.

## 5. Offer the conveniences

Ask the user whether they want each of these, since they write outside the tree:

- On the linux-x64 archive with a display, an application-menu entry:
  `~/.local/opt/proxenos/bin/install-desktop-entry`.
- `tui` on their `PATH`: `ln -s ~/.local/opt/proxenos/bin/tui ~/.local/bin/tui`. Link it rather
  than copying it, so the launcher still finds its own tree.

Then run `bin/tui check` against the completion criterion above, and continue with
[connect.md](connect.md).
