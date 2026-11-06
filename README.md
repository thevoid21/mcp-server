# Burp Suite MCP Server

Integrates Burp Suite with AI clients (Claude, Cursor, Codex, OpenCode, etc.) using the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/). The extension runs a local MCP server inside Burp, exposing Burp's data and actions as tools that AI assistants can call directly.

---

## Installation

1. Build: `./gradlew embedProxyJar` → produces `build/libs/burp-mcp-all.jar`
2. Load the JAR as a Burp extension (Extensions → Add → Java)
3. Configure the MCP tab in Burp (host, port, approvals)
4. The MCP server listens on `http://127.0.0.1:9876` by default
   - SSE endpoint: `http://127.0.0.1:9876/sse`
   - The root path (`http://127.0.0.1:9876/`) also serves the SSE stream

---

## Client Setup

Add the Burp MCP server to your AI client's configuration. The server must be running (Burp open with the extension loaded) before the client connects.

### Claude Code (CLI)

Add via the CLI (writes to `~/.claude.json` for user scope, or `.mcp.json` for project scope):

```bash
claude mcp add --transport http burpsuite http://127.0.0.1:9876/sse
```

Claude Code v2.1.265+ tries HTTP first and falls back to SSE automatically. On older versions use `--transport sse` explicitly.

Or edit `~/.claude.json` directly:

```json
{
  "mcpServers": {
    "burpsuite": {
      "type": "sse",
      "url": "http://127.0.0.1:9876/sse"
    }
  }
}
```

> **Note:** A `url` entry with no `type` is a config error in Claude Code — always include `"type": "sse"` or `"type": "http"`.

### Claude Desktop

Open **Settings → Developer → Edit Config** (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS, `%APPDATA%\Claude\claude_desktop_config.json` on Windows) and add:

```json
{
  "mcpServers": {
    "burpsuite": {
      "url": "http://127.0.0.1:9876/sse"
    }
  }
}
```

Restart Claude Desktop after saving.

Alternatively, add it through the UI: **Settings → Connectors → Add custom connector** and paste `http://127.0.0.1:9876/sse`.

### Cursor

Edit `~/.cursor/mcp.json` (global) or `.cursor/mcp.json` (project-level). Remote servers use just `url` — no `type` field:

```json
{
  "mcpServers": {
    "burpsuite": {
      "url": "http://127.0.0.1:9876/sse"
    }
  }
}
```

Restart Cursor or reload the MCP server list from **Settings → MCP**.

### OpenCode

Edit `~/.config/opencode/opencode.jsonc`:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "burpsuite": {
      "type": "remote",
      "url": "http://127.0.0.1:9876",
      "enabled": true
    }
  }
}
```

### Codex (OpenAI CLI)

Add via the CLI (writes to `~/.codex/config.toml`):

```bash
codex mcp add burpsuite --url http://127.0.0.1:9876/sse
```

Or add manually to `~/.codex/config.toml`:

```toml
[mcp_servers.burpsuite]
url = "http://127.0.0.1:9876/sse"
```

---

## Available Tools

### HTTP Requests

| Tool | Description |
|------|-------------|
| `send_http1_request` | Send an HTTP/1.1 request and return the response. Params: `content`, `targetHostname`, `targetPort`, `usesHttps` |
| `send_http2_request` | Send an HTTP/2 request and return the response. Params: `pseudoHeaders` (map), `headers` (map), `requestBody`, `targetHostname`, `targetPort`, `usesHttps` |

### Repeater & Intruder

| Tool | Description |
|------|-------------|
| `create_repeater_tab` | Create an HTTP/1.1 Repeater tab. Params: `content`, `targetHostname`, `targetPort`, `usesHttps`, `tabName` (optional) |
| `create_repeater_tab_http2` | Create an HTTP/2 Repeater tab. Params: `pseudoHeaders`, `headers`, `requestBody`, `targetHostname`, `targetPort`, `usesHttps`, `tabName` (optional) |
| `send_to_intruder` | Send a request to Intruder. Params: `content`, `targetHostname`, `targetPort`, `usesHttps`, `tabName` (optional) |

### Proxy HTTP History

| Tool | Description |
|------|-------------|
| `get_proxy_history_count` | Return the total number of items in the proxy HTTP history. |
| `get_proxy_http_history` | Return proxy HTTP history items with pagination and optional filters (see [Filters](#filters)). |
| `get_proxy_http_history_regex` | Return proxy HTTP history items whose raw content matches a regex. Params: `regex`, `caseInsensitive` (optional), plus all filters. |

> **Output format:** all history tools return a JSON object: `{"total":N,"returned":N,"offset":N,"nextOffset":N,"items":[...]}`. Use `nextOffset` to page. Items with `"_truncated":true` were cut to fit the per-item length limit — re-request with a higher `maxItemLength` to get the full content.
>
> **Large histories:** `count` is capped at 100 per call, and each call's cost depends on `count`, not on the size of the history. For 10k–40k items, see [Large Histories](#large-histories).

### WebSocket History

| Tool | Description |
|------|-------------|
| `get_websocket_history_count` | Return the total number of items in the proxy WebSocket history. |
| `get_proxy_websocket_history` | Return WebSocket history items. Params: `count`, `offset`, `newestFirst`, `hosts`, `direction` (`CLIENT_TO_SERVER` or `SERVER_TO_CLIENT`), `highlightColor`, `hasHighlight`, `hasNotes` |
| `get_proxy_websocket_history_regex` | Return WebSocket history items matching a regex. Params: `regex`, `caseInsensitive`, plus all WebSocket filters. |

### Organizer

| Tool | Description |
|------|-------------|
| `get_organizer_count` | Return the total number of items in the Organizer tab. |
| `get_organizer_items` | Return Organizer items with pagination and filters. Supports `hosts`, `methods`, `statusCodes`, `pathContains`, `headersOnly`, `highlightColor`, `hasHighlight`, `hasNotes`, `maxItemLength`. |
| `get_organizer_items_regex` | Return Organizer items matching a regex. Params: `regex`, `caseInsensitive`, plus all Organizer filters. |

### Scanner *(Burp Pro only)*

| Tool | Description |
|------|-------------|
| `get_scanner_issue_count` | Return the total number of issues found by the Burp scanner. |
| `get_scanner_issues` | Return scanner issues with pagination. Params: `count`, `offset` |

### Collaborator *(Burp Pro only)*

| Tool | Description |
|------|-------------|
| `generate_collaborator_payload` | Generate a Burp Collaborator payload for OOB testing. Params: `customData` (optional). Returns payload URL and ID. |
| `get_collaborator_interactions` | Poll Collaborator for DNS/HTTP/SMTP interactions. Params: `payloadId` (optional — omit to get all interactions) |

### Burp Configuration

| Tool | Description |
|------|-------------|
| `output_project_options` | Export current project-level options as JSON. |
| `output_user_options` | Export current user-level options as JSON. |
| `set_project_options` | Merge JSON into project-level options. Requires *Enable tools that can edit your config*. Param: `json` (must have top-level `project_options` key) |
| `set_user_options` | Merge JSON into user-level options. Requires *Enable tools that can edit your config*. Param: `json` (must have top-level `user_options` key) |

### Burp Controls

| Tool | Description |
|------|-------------|
| `set_task_execution_engine_state` | Pause or unpause Burp's task execution engine. Param: `running` (boolean) |
| `set_proxy_intercept_state` | Enable or disable Burp Proxy intercept. Param: `intercepting` (boolean) |

### Editor

| Tool | Description |
|------|-------------|
| `get_active_editor_contents` | Return the text content of the currently focused Burp message editor. |
| `set_active_editor_contents` | Set the text content of the currently focused Burp message editor. Param: `text` |

### Utilities

| Tool | Description |
|------|-------------|
| `url_encode` | URL-encode a string. Param: `content` |
| `url_decode` | URL-decode a string. Param: `content` |
| `base64_encode` | Base64-encode a string. Param: `content` |
| `base64_decode` | Base64-decode a string. Param: `content` |
| `generate_random_string` | Generate a random string. Params: `length`, `characterSet` |

---

## Filters

`get_proxy_http_history`, `get_proxy_http_history_regex`, `get_organizer_items`, and `get_organizer_items_regex` support the following optional parameters. All can be combined.

### Pagination

| Parameter | Type | Description |
|-----------|------|-------------|
| `count` | int | Items per page, 1–100. Larger values are capped at 100 (see `returned`). |
| `offset` | int | Starting position (0-based). Must be 0 or more. |
| `newestFirst` | boolean | Return newest items first (default: `true`) |

Response is always a JSON object:
```json
{"total": 47, "returned": 10, "offset": 0, "nextOffset": 10, "items": [...]}
```
`nextOffset` is omitted when you have reached the end. Items with `"_truncated": true` were cut to fit the per-item length limit.

### Filters

| Parameter | Type | Description |
|-----------|------|-------------|
| `inScopeOnly` | boolean | Only return items in Burp's target scope (HTTP history only) |
| `hosts` | string[] | Filter to specific hostnames, e.g. `["api.example.com"]` |
| `methods` | string[] | Filter by HTTP method, e.g. `["POST", "PUT", "PATCH"]` |
| `statusCodes` | int[] | Filter by response status code, e.g. `[200, 401, 403, 500]` |
| `pathContains` | string | Substring match on URL path (case-insensitive), e.g. `"/api/v2/"` |
| `excludeExtensions` | string[] | Exclude by file extension, e.g. `["png", "css", "woff2"]` |
| `mimeTypes` | string[] | Include only these MIME types (Burp enum names), e.g. `["JSON", "HTML", "SCRIPT"]` |
| `highlightColor` | string | Filter by highlight colour: `RED`, `ORANGE`, `YELLOW`, `GREEN`, `CYAN`, `BLUE`, `PINK`, `MAGENTA`, `GRAY` |
| `hasHighlight` | boolean | `true` = any highlighted item; `false` = no highlight |
| `editedOnly` | boolean | `true` = only items modified by a match-and-replace rule (HTTP history only) |
| `hasNotes` | boolean | Filter by whether the item has an annotation note |

### Output size

| Parameter | Type | Description |
|-----------|------|-------------|
| `headersOnly` | boolean | Strip request/response bodies — keeps only HTTP headers. Reduces token use 5–50× for JSON API traffic. Bodies are replaced with `<body omitted>`. |
| `maxItemLength` | int | Override the default 5 000-character per-item truncation limit. Useful for requests with large cookie jars (e.g. `50000`). |

---

## Large Histories

Proxy and Organizer histories can grow to tens of thousands of items. This section explains how the paging tools work, what they cost, and how to use them on a large history.

### How a page is built

Every paginated history call runs in two phases:

1. **Select.** The server walks the whole history and keeps the items that pass the filters. This phase works on Burp item references and builds no strings. Its result size is `total`.
2. **Encode.** Only the items on the requested page are serialised to JSON, truncated to `maxItemLength`. Nothing else is encoded.

Before this change, every call encoded every matching item and then discarded most of them. Call cost grew with the size of the history, not with `count`. Walking a 40,000-item history in pages of 20 meant 2,000 calls, each encoding all 40,000 items. The response format did not change.

### Limits

| Parameter | Rule |
|-----------|------|
| `count` | 1–100. Values above 100 are capped at 100; check `returned` in the response. Values below 1 return a tool error. |
| `offset` | 0 or more. Negative values return a tool error. |

If data access is denied, the call returns a tool error (`isError: true`) with the message `HTTP history access denied by Burp Suite` (or the matching label for WebSocket history or Organizer).

### Measured cost

Synthetic benchmark on an Apple Silicon Mac (JVM, after warm-up, minimum of 3 runs). 40,000 items averaging about 7.4 KB of request and response text each. The benchmark called the encoder directly, so it measures encoding only:

| Call | Before | After |
|------|-------:|------:|
| One page, `count=20` | 983 ms | 0.7 ms |
| One page, `count=100` | 983 ms (same work as `count=20`) | 3 ms |
| Walk all 40,000 items, 20 per page (2,000 calls) | ≈ 33 min | ≈ 1.4 s |

The walk figures are derived: calls × per-call time. The "before" numbers leave out Burp's own cost of turning each message into text, so the real "before" is higher. Filtering still touches every item once per call, so the "after" numbers do not include the filter pass on real Burp objects. Time a single call with `count=1` on your own history to get your real numbers.

### Using large histories

- **Check the size first.** Call `get_proxy_history_count`, or read `total` from any response.
- **Filter before you page.** `hosts`, `methods`, `statusCodes`, `pathContains`, `mimeTypes`, and `highlightColor` remove items before any output is built. Use a small `count` (10–20) and `headersOnly=true` when you are scanning.
- **Don't walk the whole history.** Ask for what you need. Use filters for "requests to this host", and a filtered regex for "anything that mentions this".
- **Narrow filters also speed up regex.** The regex runs only on items that pass the other filters. A broad regex over the full history still scans every body, once per call.

### Known limitations

- **Pages can shift while traffic arrives.** Pages are offset-based. With `newestFirst` (the default), each new request moves older items down one position, so the next page can repeat an item or skip one. Keep pages small, re-check `total`, and narrow filters when precision matters. Stable cursors based on Burp item IDs would fix this and are a possible follow-up.
- **Regex is a full scan of the filtered set on every call.** There is no index or cache.
- **The filter pass is O(N) per call.** Each call still reads the metadata of every item once. That is fast for simple checks, but it grows linearly with the history.
- **Approval prompts repeat per page** when *Require approval for project data access* is on. Choose *Always Allow* for that data type to stop the prompts.

### Design choices

| Option | Verdict |
|--------|---------|
| Cache the encoded history | Rejected. Uses a lot of memory and goes stale as traffic arrives. |
| Let Burp filter for us | Rejected. `ProxyHistoryFilter` is a plain predicate, so Burp does not filter any faster. |
| Use item IDs as cursors | Deferred. Burp items expose `id()`, which would fix drift. It changes the request contract, so it's a separate change. |
| **Select, then encode only the page** | **Chosen.** The fix is local to the paginated helper, the response format is unchanged, and per-call cost depends on `count`. |

In the code, see `paginate` and `mcpPaginatedTool` in [`McpTool.kt`](src/main/kotlin/net/portswigger/mcp/tools/McpTool.kt). The unit tests are in [`PaginateTest.kt`](src/test/kotlin/net/portswigger/mcp/tools/PaginateTest.kt).

---

## Security & Approvals

Two approval layers gate the tools. Both prompt **inside the Burp UI** — watch for dialogs:

1. **HTTP request approval** (`send_http1_request`, `send_http2_request`, Repeater/Intruder tools):
   on the first request to any host, Burp shows:
   `Allow Once` / `Always Allow Host` / `Always Allow Host:Port` / `Deny`.
   "Always Allow" adds the host to the auto-approve target list (persisted in config).
2. **Data access approval** (history/Organizer/WebSocket read tools, when *Require approval for project data access* is on):
   Burp shows `Allow Once` / `Always Allow <HTTP history|WebSocket history|Organizer items>` / `Deny` on **every call**
   until you pick *Always Allow* for that data type. Paging through history therefore prompts once per page.
   Note: history items may contain sensitive data from previous web sessions.

Additional gates:
- `set_project_options` / `set_user_options` require the *Enable tools that can edit your config* checkbox in the MCP tab.
- Collaborator tools require Burp Pro.

### Request content normalization

MCP clients often emit `\r\n` as the literal 4-character sequence backslash-r-backslash-n in
JSON tool arguments instead of real CRLF bytes. The server normalizes the request **prelude**
(request line + headers, up to the first blank line) to proper CRLF; request **bodies are
preserved verbatim** so escape sequences inside JSON/binary bodies stay byte-exact.
If a strict backend still rejects your request, check for duplicated headers
(e.g. two `Content-Length` lines) — Cloudflare-class edges return 400 for that.

---

## Configuration Defaults

| Setting | Default |
|---------|---------|
| Require approval for HTTP requests | `false` |
| Require approval for project data access | `false` |
| Enable tools that can edit your config | `true` |

> **Note:** Defaults apply to new installs. Existing Burp projects retain previously saved values — toggle the settings manually in the MCP tab if needed.
