# Upgrade

**Done when** the new tree's `bin/tui status --json` shows the Runtime running and the tunnel
`Connected`, and the user's Workspaces are listed as before. If `connector` reads
`Unconfirmed`, the plugin must also be recreated and `await-listing` must exit 0 again.
`~/.local/opt/proxenos.previous` stays until the user says the new version works.

1. `cat ~/.local/opt/proxenos/VERSION` gives the installed version; a tree without the file is
   0.3.1 or older. Compare it with the latest release tag ([install.md](install.md) step 2). If
   they match, there is nothing to do.
2. Download and verify the new archive exactly as in [install.md](install.md) step 2, using the
   same archive flavour as before: `jre/` as a real directory means linux-x64. If the old tree
   has a `jre` symlink, note where it points.
3. Stop the Runtime, with consent (SKILL.md's rule). There is no version handshake between a
   frontend and a Runtime from different trees, so the old Runtime must not keep running.
4. Swap the trees, keeping one previous copy:

   ```sh
   rm -rf ~/.local/opt/proxenos.previous
   mv ~/.local/opt/proxenos ~/.local/opt/proxenos.previous
   tar -xzf "$archive" -C ~/.local/opt
   mv ~/.local/opt/"${archive%.tar.gz}" ~/.local/opt/proxenos
   ```

5. Link `jre/` again if the old tree had the link ([java.md](java.md#point-proxenos-at-it)).
6. Run `bin/install-tunnel-client`, since the new release may pin a newer tunnel client, and
   `bin/install-desktop-entry` if the menu entry was installed.
7. Run `bin/tui start`, then `bin/tui status --json`. Workspaces, Activity and credentials live
   outside the tree and carry over.
8. If `connector` is `Unconfirmed`, this version serves a different tool catalog, and the
   ChatGPT plugin still holds the old one. Hand the user the plugin steps in
   [connect.md](connect.md) §4. They delete the old plugin and create it again, then confirm it
   in a frontend. Run §5 again.

To roll back, stop the Runtime, move `proxenos.previous` back to `proxenos`, and start it. Once
the user confirms the new version works, delete `~/.local/opt/proxenos.previous`.
