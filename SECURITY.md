# Security policy

Proxenos lets a remote model read files, write files and run commands on your machine, within
the Access Level you grant each Workspace. Bugs that let a call escape those limits are the ones
that matter most here.

## Reporting a vulnerability

Please **do not open a public issue**. Report privately through GitHub's
[private vulnerability reporting](https://github.com/kzagoris/proxenos/security/advisories/new),
with the version or commit, the steps to reproduce, and what an attacker gains.

You should get an acknowledgement within a week. Fixes are released as soon as they are ready,
and the advisory is published once users have had a chance to update.

## In scope

- A call that reads, writes or runs anything beyond its Workspace's Access Level, or outside its
  Root where confinement applies (see `CONTEXT.md` and `docs/SPEC.md`).
- A withheld (None) or Broken Workspace that is visible or reachable from a tool call.
- The control socket accepting a peer other than the owning user.
- Credentials leaking into logs, Activity, child process environments or tool output.

## Out of scope

- Anything a Workspace set to **Command** does by running commands: at that level the model runs
  commands as your user, by design. Grant it only to directories you would let it work in.
- Vulnerabilities in ChatGPT, the OpenAI Secure MCP Tunnel or `tunnel-client` themselves; report
  those to OpenAI.
