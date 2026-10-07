Proxenos exposes selected local directories to ChatGPT: a background Runtime answers its tool
calls, and the `tui` and `gui` frontends manage it. Linux only.

- Use the domain language of `CONTEXT.md`: its terms exactly, in place of the synonyms it lists.
- `docs/adr/` holds decisions a later change would otherwise undo. Read the ADR covering any
  behaviour you change; record a new one when you reverse it.
- Read `CODING_STANDARDS.md` before writing code, a test or a module dependency.
- `./gradlew build` is the full check; `test` alone skips the module-boundary check.
  `./gradlew runGui` opens the installed GUI; `tui/drive.py` and `gui/drive.py` drive the frontends.
- Credentials live in `$XDG_CONFIG_HOME/proxenos/credentials`. Write none inside a checkout,
  tests included.
