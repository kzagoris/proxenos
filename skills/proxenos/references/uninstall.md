# Uninstall

**Done when** the program is gone from this machine, what the user chose to keep is still there,
and they know about the two clean-ups only they can do.

1. Stop the Runtime, with consent (SKILL.md's rule): `~/.local/opt/proxenos/bin/tui stop`.
2. Remove the program:

   ```sh
   ~/.local/opt/proxenos/bin/install-desktop-entry --remove   # linux-x64, if the menu entry was installed
   rm -rf ~/.local/opt/proxenos ~/.local/opt/proxenos.previous
   ```

   Remove a `~/.local/bin/tui` that is a symlink into that tree. If the user installed the
   systemd unit, also run `systemctl --user disable --now proxenos.service` and remove
   `~/.config/systemd/user/proxenos.service`.
3. Ask before removing what the user may want for a later reinstall. By default, keep it:
   - `~/.config/proxenos/`: the credentials, `config.toml` and the desktop setup's progress.
   - `~/.local/state/proxenos/`: Workspace registrations, Activity, the connector confirmation
     and `tools/tunnel-client`.

   `XDG_CONFIG_HOME`, `XDG_STATE_HOME` or `PROXENOS_STATE_DIR` move these, so check the
   environment before deleting.
4. Tell the user to finish on OpenAI's side, which nothing on this machine can do:
   - Revoke the runtime key in Platform, under *Runtime API keys*, and delete the tunnel if
     nothing else uses it.
   - Delete the Proxenos plugin in ChatGPT.
