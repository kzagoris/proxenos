# Java for the portable archive

The linux-x64 archive carries its own Java, so none of this applies to it.

**Done when** `~/.local/opt/proxenos/bin/tui check` runs and prints its requirements. An older
Java fails before that with `UnsupportedClassVersionError … class file version 70.0`.

## What to install

Install a Java **26 or newer**, and pick the newest release available. As of October 2026 that
is 27: Java 26 has stopped receiving updates, and no long-term-support release is new enough
before Java 29. A headless runtime (JRE) is enough.

Match the distribution on `ID` or `ID_LIKE` in `/etc/os-release` (Omarchy reports `ID=omarchy`,
`ID_LIKE=arch`). The package names below go stale. Before installing, ask the package manager
which version a package would install, and move to the next source if it is older than 26:

| Distribution | Ask | Install |
| --- | --- | --- |
| Arch, Manjaro | `pacman -Si jre-openjdk-headless` | `sudo pacman -S jre-openjdk-headless` (tracks the newest release) |
| Fedora | `dnf info java-latest-openjdk-headless` | `sudo dnf install java-latest-openjdk-headless` |
| Ubuntu | `apt-cache policy openjdk-27-jre-headless` (try the newest number first) | `sudo apt install openjdk-27-jre-headless` |
| Debian, or any of the above when too old | Adoptium's repository, below | `temurin-<N>-jre` (on RPM also `-jre-headless`); `temurin-<N>-jdk` if no JRE is listed |

Prefer the sources in this order:
1. The distribution's own packages.
2. Adoptium's repository.
3. A user-level install, only when the user declines sudo.

The first two get security fixes with ordinary system updates, which matters for a process that
runs commands on ChatGPT's request. Show the exact commands and get a yes before running them
(SKILL.md's sudo rule).

### Adoptium's repository

From <https://adoptium.net/installation/linux/>. Check that page if a command fails.

Debian and Ubuntu (on Linux Mint, use `UBUNTU_CODENAME`):

```sh
sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null
echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && apt-cache policy temurin-27-jre
```

Fedora and RHEL, then `dnf info temurin-27-jre-headless`:

```sh
sudo tee /etc/yum.repos.d/adoptium.repo > /dev/null <<EOF
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/$(. /etc/os-release; echo $ID)/\$releasever/\$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF
```

openSUSE, then `zypper info temurin-27-jdk`:

```sh
sudo zypper ar -f https://packages.adoptium.net/artifactory/rpm/opensuse/$(. /etc/os-release; echo $VERSION_ID)/$(uname -m) adoptium
```

### Without sudo

With [mise](https://mise.jdx.dev): `mise install java@temurin-<N>` (list candidates with
`mise ls-remote java | grep '^temurin-'`), then the home is `mise where java@temurin-<N>`.
Without mise, download Adoptium's JRE tarball for the machine's architecture from
<https://adoptium.net/temurin/releases/>, check it against its published SHA-256, and unpack it
under `~/.local/opt/`. Tell the user plainly that nothing will update this Java, and that it
needs replacing when a security release comes out.

## Point Proxenos at it

Link the chosen Java's home (the directory holding `bin/java`) into the tree as `jre/`:

```sh
home=$(dirname "$(dirname "$(readlink -f /path/to/chosen/bin/java)")")
ln -sfn "$home" ~/.local/opt/proxenos/jre
```

Every launcher uses `jre/bin/java` ahead of `JAVA_HOME` and `PATH`, however it is started (a
terminal, the desktop entry, a systemd unit), and nothing else on the machine changes. The
machine's default Java (`archlinux-java`, `update-alternatives`) stays as it is, because other
software relies on it.

If a later system update removes that Java's directory, the launchers fall back to `JAVA_HOME`,
then `PATH`, and an old one fails with `UnsupportedClassVersionError`. Link `jre/` to the new
home then. Check the completion criterion above.
