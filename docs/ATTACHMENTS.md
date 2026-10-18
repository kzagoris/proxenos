# PNG and PDF attachments

`import_attachment` saves a ChatGPT attachment in a selected **Write** Workspace.
`export_file` returns a local file from a selected **Read** Workspace. Separate names keep
routing, Access Level, and the direction of the bytes explicit; neither tool is a general
asset fetcher.

## Import

Attach a PNG or PDF to the conversation, name the Workspace and an explicit destination
relative to its Root, and ask ChatGPT to save it. The host supplies the top-level `file`
object through `_meta["openai/fileParams"]: ["file"]`:

- `download_url` and `file_id` are required, nonempty strings.
- `mime_type` and `file_name` are optional strings. The name never chooses the destination.
- `path`, `workspace`, and a unique `request_id` are required. A sandbox path, local filename,
  or invented URL is not an attachment reference.

The Runtime admits the Workspace at Write, confines the destination, checks its parent and
regular-file status, and checks the Delivery key before starting network work. Missing parents
are not created. A supplied MIME type and the HTTP MIME type must agree with the observed PNG
or PDF signature; `application/octet-stream` permits signature detection. This is a signature
check, not a claim that a document is well formed or safe to open.

### Trusted origins

The exact allowed HTTPS hosts are `files.oaiusercontent.com` and
`oaisdmntprdenmarkeast.blob.core.windows.net`. The latter is the specific storage account
observed in authenticated attachment transfers. This is Proxenos's deliberately narrow policy,
not an official OpenAI hostname guarantee. Other Azure accounts and subdomains are refused.
A new host requires provenance review and a code/catalog release, not a caller-supplied override.

Userinfo, fragments, nonstandard ports, and every redirect are refused. All resolved addresses
must be public unicast addresses; local, private, shared, documentation, and transition ranges
are excluded. The HTTP connection dials a checked address directly while retaining the original
Host header, TLS certificate hostname verification, and SNI. A second DNS lookup cannot rebind
the connection. No proxy, cookies, caller headers, or ambient HTTP credentials are used.
Reference fields are bounded (URL 8 KiB, id/name 512 characters, MIME 128 characters).
Signed URLs and reference identifiers are omitted from Activity and error messages. Download
exceptions and remote error bodies are never quoted.

### Effects and Delivery

The download is streamed into bounded memory before any destination or staging file is touched.
HTTP rejection (including missing/expired references), timeout, cap exhaustion, and MIME/signature
refusal return Failed and leave the destination unchanged. Interruption during download records
Failed with the known absence of staging and replacement, then propagates cancellation.

After download the Runtime re-checks the Workspace's Root, Access Level, and resolved destination.
It stages bytes beside the target, flushes them, and atomically replaces the target using the same
file-writing path as `write_file`. Existing mode bits survive; replacing the inode breaks
hardlinks. An error at replacement is Uncertain: inspect Activity and disk before another
intentional mutation. There is no rollback or replay from Activity. Staging files are removed
on ordinary failures; abrupt process death can leave one, and an unfinished Operation is Lost.
The filesystem confinement has the existing resolve/check race against unrelated local programs
changing symlinks; it is not an OS sandbox.

A repeated successful Delivery with the same arguments and `request_id` receives the recorded
metadata without another download or replacement, even if the reference has since expired or
the Workspace's Access Level was lowered. A concurrent repeat waits for the first Delivery.
Changing a reference under a retained key is refused as a different Operation. Failed work
releases its key because it left no mutation to repeat (ADR 0004).

For an expired or invalid reference, reattach the file and ask ChatGPT to use the new
host-supplied reference with a fresh `request_id`. Never edit expiry parameters, reuse an old
signed URL, or substitute a sandbox path. A refreshed reference is a new intentional Operation.

## Export

Ask ChatGPT to export an explicit Root-relative PNG or PDF using `export_file`. A regular file
is read with a streaming cap. The reply includes observed MIME, actual bytes, and SHA-256 plus
an MCP **embedded binary resource** carrying the exact Base64-encoded bytes. Binary content
appears once, outside structured metadata. The resource URI labels these returned bytes; it
is not an advertised `resource_link`, public download endpoint, or `resources/read` route.

For PNG only, `vision: true` adds image content for model vision. The embedded resource remains
the downloadable file; image content alone is not a byte-fidelity export. ChatGPT can ask for
**file-materialization approval** before it makes a download available. Approving that prompt
is part of the host workflow, not a Runtime capability to upload files into ChatGPT storage.

## Limits and verification

The raw limit in **both directions is 1,048,576 bytes (1 MiB)**. Oversized files fail whole;
there is no partial binary reply, pagination, or silent truncation. Downloads have a 25-second
budget and 5-second connection/socket inactivity limits. HTTP content encoding is refused.
The returned size and SHA-256 describe the actual bytes, not an untrusted declared size.

One maximum-size Base64 body is 1,398,104 bytes; optional PNG vision doubles that to 2,796,208.
The adapter measures the complete serialized MCP result and refuses results above 3 MiB,
leaving room for outer framing below the tunnel's documented 10 MiB envelope ceiling. It never
passes binary content through the 63 KiB text reply truncator. The cap is intentionally lower
than the transport ceiling and is not a promise to transfer a 10 MiB file.

Automated checks exercise the existing Operations/Activity and real MCP Unix-socket boundaries,
with a real local HTTPS responder for imports: exact bytes/checksum metadata, replay, Access
Level and confinement refusals, escaping/dangling symlinks, untrusted origins/private addresses,
HTTP expiry refusal, redirects, cap exhaustion, MIME mismatch, and cancellation. The maximum
1 MiB export (including vision) and cap-plus-one refusal are checked through the SDK envelope.
Authenticated ChatGPT verification on 2026-10-18 also imported a PNG (169 bytes), PDF (625 bytes),
and a 1 MiB PNG with original hashes intact. Different, never-uploaded local PNG/PDF exports
materialized after approval and matched independently computed host hashes. A 1 MiB PNG export
with vision also materialized and matched its independent size/hash.

An oversized PNG upload was supplied by ChatGPT as a different 134-byte image representation,
so that run did **not** verify oversized rejection. The Runtime reports and hashes the bytes the
host actually supplies; compare with the expected original hash when original-file identity matters.
A rendered image or thumbnail reference can differ from the uploaded file. Prefer the original
file reference, and treat any size/hash mismatch as a failed fidelity check.
The subsequent original-PDF check supplied 1,048,577 bytes and was refused at the streaming cap;
its destination remained absent. Thus the practical 1 MiB limit has host evidence in both
directions, and the oversized original-file refusal has separate PDF evidence.

Natural expiration was checked with an unchanged, genuine PDF URL: it expired at
2026-10-18 10:04:58 UTC, and a new import using that exact reference at 10:06:52 returned HTTP 403
and Failed while preserving an existing destination byte for byte. No expiry parameter was edited.
Reattaching the same PDF through ChatGPT then supplied a fresh reference: the import succeeded
at 10:09:29 with the original 625-byte size and SHA-256. These are natural-expiry and actual
reattachment checks, separate from the controlled HTTP refusal tests.


## Upgrade

This release adds `import_attachment` and `export_file` to the catalog (15 tools). Both the
file-reference metadata and the new input/output contracts participate in the catalog fingerprint,
so an older acknowledgement becomes **Unconfirmed**. After upgrading and starting the Runtime:

1. In ChatGPT, open the plugin's **Manage** page and choose **Refresh tools**.
2. Review the new tools and start a new conversation. Recreate the connection if refresh fails.
3. Verify the file transfer and then choose **I updated the connector** in Proxenos.

The acknowledgement records your word; Connected does not measure ChatGPT's saved catalog.
See [installation and upgrade](INSTALL.md) and [ADR 0014](https://github.com/kzagoris/proxenos/blob/master/docs/adr/0014-refresh-the-connector-catalog-explicitly.md).

The host file object follows [OpenAI's File APIs reference](https://developers.openai.com/plugins/reference#file-apis).
The envelope ceiling is documented in [Secure MCP Tunnel limits](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels#limits).
