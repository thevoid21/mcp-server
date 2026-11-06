package net.portswigger.mcp.tools

/**
 * Server-level guidance sent to MCP clients when they connect. Clients that support
 * server instructions pass this to the model before any tool is called.
 * Keep it short and specific; per-tool and per-parameter guidance lives in the
 * tool descriptions and @ParamDoc annotations.
 */
val SERVER_INSTRUCTIONS: String = """
This server gives you access to a running Burp Suite instance. Tools read and change the user's live Burp project, and some of them send real traffic to targets.

Reading history (proxy HTTP history, Organizer, WebSocket history):
- Call get_proxy_history_count (or get_organizer_count, get_websocket_history_count) first to learn how big the set is.
- Filter before paging. Filters remove items before the page is built, so they keep results focused and calls fast.
- Page with count (1 to 100) and offset. Use the nextOffset value from each response to fetch the next page. When nextOffset is missing, you have reached the end.
- Results are newest first by default. For broad scans, set headersOnly=true, then fetch full items only for the few that matter.
- On large histories, prefer filters and a small count over walking the whole history. Regex tools search each item's raw request and response text.
- Pages can shift while new traffic arrives. Check total again if exact results matter.

Sending and creating requests:
- send_http1_request and send_http2_request send real traffic to the target. If the user has enabled approval, Burp asks them to approve each new host before anything is sent.
- create_repeater_tab, create_repeater_tab_http2 and send_to_intruder only open a tab in Burp. They do not send the request. Use them when the user wants to inspect or test a request by hand.
- Raw HTTP/1.1 requests need CRLF line endings and a blank line before the body.
- HTTP/2 tools take pseudo-headers, headers and the body as separate fields. Never put headers in the body.

Permissions and errors:
- Reading history or Organizer data may need the user's approval in Burp. A denied request returns an error or a denial message. Tell the user; do not retry in a loop.
- set_project_options and set_user_options work only when the user has enabled "Enable tools that can edit your config" in the MCP tab. Export the current settings first and keep their JSON structure.

Burp Suite Professional only: the scanner tools (get_scanner_issues, get_scanner_issue_count) and the Collaborator tools (generate_collaborator_payload, get_collaborator_interactions). To test for out-of-band behaviour, generate a payload, inject it into a request, then poll for interactions.
""".trimIndent()
