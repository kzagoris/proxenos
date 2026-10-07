# Coding standards

- *Simplicity*: choose the design with the fewest moving parts (abstractions, options, states,
  layers) that meets the need, and add more only when a concrete case demands it.
- A frontend (`tui`, `gui`) reaches the core only through `core-api` and the control socket.
  What every frontend must agree on — attaching to and starting the Runtime, and the domain's
  wording — lives in `frontend`, which draws nothing.
- A new `moduleBoundaries { mayReach(...) }` edge arrives with the reason the dependency is
  wanted, stated in the change that adds it.
- Tests exercise behaviour through public interfaces: each can fail on a real defect and keeps
  passing through a refactor that leaves the behaviour unchanged. No *tautological* or
  *change-detector* tests.
- Keep comments at the density of the surrounding code: they explain *why*, citing an ADR where
  one applies.
