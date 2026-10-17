# Refresh the connector catalog explicitly

ChatGPT's **Manage → Refresh tools** successfully replaced a developer connection's catalog
through the Secure MCP Tunnel on 2026-10-17: a new conversation discovered and invoked temporary
file-reference and binary-output probes added after the connection was created. This amends
ADR 0007's measured assumption that the catalog could only change by recreating the connection.

Guide users to refresh an existing connection, review its tools, and verify an Operation in a new
conversation. Creation remains necessary on first run; recreation remains the fallback when
refresh fails. Do not infer that an already open conversation has adopted the new catalog.

Keep the catalog fingerprint and explicit acknowledgement. The Runtime still cannot inspect
ChatGPT's saved catalog or measure whether the user updated it; Connected measures the tunnel,
and Confirmed records the user's word. Automatic acknowledgement after a local catalog change
would erase that distinction. The acknowledgement therefore describes a current tool list,
rather than requiring the user to claim they recreated the connection.

The experiment and its limits are recorded in [issue #2](https://github.com/kzagoris/proxenos/issues/2).
