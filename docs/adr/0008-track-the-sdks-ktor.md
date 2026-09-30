# Ktor tracks the MCP SDK, and does not float ahead of it

Two modules put Ktor on the Runtime's classpath. `mcp` brings `ktor-server-cio`, because
`kotlin-sdk-server` ships no engine, and `core` brings `ktor-client-cio` to read the tunnel
child's `/metrics` over a Unix socket. The SDK itself also depends on Ktor — its
`mcpStreamableHttp` is a Ktor server plugin — so there is one Ktor on the classpath whatever we
write, and the only question is who picks its version. That was left owed: **track the
version the SDK resolves, or float ahead of it**.

We track it. The Ktor pin in `gradle/libs.versions.toml` is the version `kotlin-sdk-server`
resolves (3.5.1 against SDK 0.15.0), and it moves when the SDK moves, in the same change that
bumps the SDK. Bumping the SDK is therefore the one moment Ktor is looked at, and the question is
answered by reading, not remembering:

```
./gradlew :mcp:dependencyInsight --configuration runtimeClasspath --dependency io.ktor:ktor-server-core
```

It must show one version with no `->`. An arrow means Gradle is resolving a conflict between the
catalog and the SDK, and the catalog is quoting a version the Runtime does not run.

## Why

**The SDK is the part that is hard to replace, and it is tested against one Ktor.** Ktor 3.x
promises binary compatibility for public API across minor releases, but `mcpStreamableHttp`
installs routes, reads headers and enforces `allowedHosts` from inside Ktor's pipeline, and it is
the SDK's CI, not Ktor's, that proves that works. Floating ahead makes every Runtime the first
place that pairing is exercised. The failures that pairing produces are not the kind a test
suite catches early: the one found so far — the SDK's DNS-rebinding check answering
`403 Invalid Host` — was found by a live tunnel, not by a unit test.

**Nothing we use needs a newer Ktor.** The one Ktor feature this design depends on beyond the
SDK's own is Unix domain socket support in CIO, both as a server connector and as a client
target, which arrived in 3.2.0. Every newer release is capability we would not call.

**One variable at a time.** The Unix-socket spike pinned 3.5.1 rather than the then-newest 3.6.0
for exactly this reason, and the transport it proved is the transport we ship. A float would
change what was measured without re-measuring it.

**The cost of tracking is small.** When the SDK moves, the catalog's Ktor line changes in the
same commit as the SDK line, and nothing else has to be decided.

## Considered options

**Float ahead** — take each Ktor release as it lands — was rejected. It buys fixes and features
in modules we mostly do not call, at the price of running an SDK/Ktor pair nobody else has
tested, and it makes the catalog's Ktor line an independent decision to revisit every Ktor
release instead of a consequence of the SDK line.

**Leave Ktor out of the catalog entirely** and let the SDK's transitive version win was rejected
too: `mcp` and `core` still have to name `ktor-server-cio` and `ktor-client-cio` at *some*
version, and an unpinned one is exactly the drift the catalog exists to prevent.

## The one exception

A **security fix** in a Ktor module the Runtime actually loads — CIO, server core, the client —
that the SDK has not yet picked up is reason to float to the *patch* release that carries it,
and only that far. The catalog comment then names the advisory and says the pin returns to
tracking when the SDK catches up, and the `dependencyInsight` check above shows the arrow on
purpose. Anything else — a bug fix we would like, a feature, "the newest one" — waits for the
SDK.

## Consequences

Ktor 3.5.2 and 3.6.0 exist as this is written, and the Runtime runs neither. That is the
decision working, not an oversight.

The same rule already governed `kotlinx-serialization` informally — its catalog comment said
"pinned to what kotlin-sdk-server resolves" — and `kotlinx-coroutines` in practice, since 1.11.0 is
what the SDK resolves. Both catalog lines now say they track the SDK, and this record is the
reason for all three.

A build-wide `failOnVersionConflict()` was tried as an enforcement and dropped: the classpath
already carries a dozen harmless `kotlin-stdlib` upgrades (2.3.x → 2.4.20) that it would reject,
so it cannot say anything about Ktor without first being taught to ignore everything else.
