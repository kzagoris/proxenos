# Complete-file batch efficiency

Measurements for [issue #4](https://github.com/kzagoris/proxenos/issues/4), 2026-10-18.

`read_files` now counts escaped UTF-8 once when allocating file content. The reader already
charges JSON escaping; the old allocator divided that allowance by six again. The shared
payload allowance is larger, defaults are included in its serialized size, and per-item
bounds still leave room for other results. The complete MCP response ceiling remains 64 KiB.
There are no catalog or schema changes.

## Method

All builds read the same disposable Git repository, archived from
`c424f6f446dbbe651640f603884aec6954d4eb8c` and committed locally to give it a clean Git state.
Orientation lists Workspaces and the Root, reads AGENTS.md, CONTEXT.md, settings.gradle.kts,
build.gradle.kts and gradle/libs.versions.toml completely (27,754 source bytes), then reads
Git status. Only explicit named files are read; no automatic instruction or credential reads
were added. Each synthetic workload reads just one file:

- Escaped: U+0001, quote and backslash repeated 10,000 times (30,000 source bytes).
- Unicode: `🙂世界` repeated 5,000 times (50,000 UTF-8 bytes).
- Long line: 70,000 ASCII `x` bytes without a newline.

Every continuation is followed, including byte offsets within a line and unchanged
`not_processed` ranges. Reconstructed text must equal the source exactly. Every complete
JSON-RPC response is checked against 65,536 bytes. Response component sizes count the
serialized `content` array and `structuredContent` object separately, including escaping;
remaining bytes are the tool-result and JSON-RPC envelopes. SSE framing and HTTP headers
are excluded, as are initialization calls and their time.

Three measured runs follow a complete warm-up pass per build. Batch and single reads
alternate order between repetitions. The historical 11-tool build is `ba52ddf`; the enhanced
build is `c424f6f`; the candidate is this change on `3f5d2d4`. Measurements use real HTTP
MCP calls over a disposable Unix socket, a real WorkspaceOperations pipeline and Activity.
No tunnel, ChatGPT host or normal user Runtime participates. Linux 7.2.11, Temurin Java 26,
and the repository's Gradle wrapper are used. Timings are local sequential workload times,
not ChatGPT latency. Builds run in separate JVMs; three samples do not establish a general
latency improvement. Raw runs and the measurement harness remain in private verification
material; the endpoint continuation scenario is retained in the public test suite.

## Orientation

| Build and read strategy | Calls | Complete response bytes (range) | Median ms (range) |
| --- | ---: | ---: | ---: |
| 11-tool single reads | 8 | 30,518–30,518 | 92.55 (89.14–96.91) |
| Enhanced batch | 10 | 71,010–71,010 | 73.41 (65.82–83.15) |
| Candidate batch | 5 | 62,773–62,778 | 58.54 (51.71–63.50) |
| Candidate single reads | 8 | 62,576–62,584 | 62.18 (58.66–85.87) |

The candidate halves batch workflow calls (10 → 5), with two read batches instead of seven.
It still sends roughly twice the bytes of the original 11-tool build: structured and
readable representations both carry file text. Comparing only raw payload sizes would hide
that cost. There is no token-savings claim.

## Large single files

| Workload and strategy | Calls | Complete response bytes (range) | Median ms (range) |
| --- | ---: | ---: | ---: |
| escaped, enhanced batch | 53 | 233,222–233,222 | 313.47 (302.77–368.33) |
| escaped, candidate batch | 7 | 204,221–204,228 | 65.45 (55.18–71.23) |
| escaped, candidate single | 5 | 202,567–202,572 | 47.07 (45.92–60.25) |
| unicode, enhanced batch | 27 | 116,842–116,842 | 149.02 (143.45–191.27) |
| unicode, candidate batch | 4 | 102,335–102,339 | 35.43 (33.82–42.25) |
| unicode, candidate single | 3 | 101,467–101,470 | 26.52 (24.66–40.39) |
| long, enhanced batch | 37 | 162,930–162,930 | 195.42 (190.90–225.56) |
| long, candidate batch | 5 | 142,935–142,940 | 57.25 (45.59–60.00) |
| long, candidate single | 3 | 141,449–141,452 | 30.59 (28.49–32.61) |

Batch reads make much more progress per reply, but `read_file` still needs fewer calls for
one large file. Prefer it for that case; batching is useful when several explicit files or
small review ranges share a reply. Both tools still have a line ceiling: absence of a byte
cap does not imply the entire file was returned. Follow continuation until the requested
range is complete, retaining its original ending line. Concurrent changes can invalidate
cursors; neither a batch nor successive reads promise a snapshot.

## Serialized components

These totals are for the same workloads above, across all replies, not only the first page.

| Workload | Enhanced batch: readable / structured bytes | Candidate batch: readable / structured bytes |
| --- | ---: | ---: |
| orientation | 34,012 / 36,148 | 30,149 / 32,204 |
| escaped | 113,309 / 115,408 | 101,595 / 102,038 |
| unicode | 56,692 / 57,855 | 50,832 / 51,167 |
| long | 79,131 / 80,654 | 71,072 / 71,443 |

The saved bytes come from fewer repeated headers, item fields and envelopes. The file text
and its two representations remain intact. Larger per-reply retention is bounded; file
scanning, binary rejection, independent failures, input order, warnings and mutation/Delivery
semantics are unchanged. The endpoint test reconstructs 19 files alongside a refused path,
with escaped filenames, Unicode, long lines and shared-budget exhaustion, while checking
that the MCP guard never drops the typed batch result.
