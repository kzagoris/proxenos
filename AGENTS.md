# AGENTS.md

Guidance for coding agents and contributors working in this repository.

## What this is

Proxenos exposes selected local directories to ChatGPT over the OpenAI Secure MCP Tunnel. A
background Runtime answers MCP tool calls; a terminal dashboard (`tui`) manages Workspaces and
shows Activity. Linux only.

## Read first

- `CONTEXT.md` — the domain language. Use its terms exactly (Workspace, Root, Access Level,
  Operation, Activity, …) and avoid the synonyms it lists.
- `docs/adr/` — decisions whose reasoning a future change would otherwise undo. Read the relevant
  ADR before changing the behaviour it covers; record a new one when you reverse it.

## Build and test

The toolchain is JDK 26 and Gradle 9.7.1, pinned in `mise.toml` and the Gradle wrapper.

```bash
./gradlew build                 # compile, unit tests, module-boundary check
./gradlew installDist           # the runnable tree in build/install/proxenos/
./gradlew runGui                # install, then open bin/gui on your session's display
python3 tui/drive.py            # pty harness for the dashboard; needs installDist first
```

## Modules

`core-api`, `core`, `mcp`, `control`, `frontend`, `runtime`, `tui`. Each module declares what it
may reach in a `moduleBoundaries { mayReach(...) }` block, and the build fails when a module
reaches anything else, transitively. If a new dependency is wanted, declare it there and justify
it in the change; frontends must reach the core only through `core-api` and the control socket.
What every frontend must agree on — attaching and starting the Runtime, and the domain's
wording — lives in `frontend`, which draws nothing.

## Conventions

- Simplicity over complexity. Choose the design with the fewest moving parts (abstractions,
  options, states, layers) that meets the need, and add more only when a concrete case demands it.
- Tests exercise behaviour through public interfaces. No tautological or change-detector tests.
- Credentials never live inside a checkout; `.gitignore` refuses `credentials` and `.env`.
- Keep comments at the density of the surrounding code: they explain *why*, citing an ADR where one applies.
