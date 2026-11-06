package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.PAUSED
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.RUNNING
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.organizer.OrganizerItem
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.scanner.audit.issues.AuditIssue
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.encodeHistoryItem
import net.portswigger.mcp.schema.toSerializableForm
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.filterConfigCredentials
import java.awt.KeyboardFocusManager
import java.util.regex.Pattern
import javax.swing.JTextArea

private suspend fun checkDataAccessOrDeny(
    accessType: DataAccessType, config: McpConfig, api: MontoyaApi, logMessage: String
): Boolean {
    val allowed = DataAccessSecurity.checkDataAccessPermission(accessType, config)
    if (!allowed) {
        api.logging().logToOutput("MCP $logMessage access denied")
        return false
    }
    api.logging().logToOutput("MCP $logMessage access granted")
    return true
}

/** Throws when the user has denied access, so paginated tools report it as a tool error. */
private fun requireDataAccess(
    accessType: DataAccessType, config: McpConfig, api: MontoyaApi, label: String
) {
    val allowed = runBlocking { checkDataAccessOrDeny(accessType, config, api, label) }
    if (!allowed) error("$label access denied by Burp Suite")
}

private fun buildHttp2HeaderList(
    pseudoHeaders: Map<String, String>, headers: Map<String, String>
): List<HttpHeader> {
    val orderedPseudoHeaderNames = listOf(":scheme", ":method", ":path", ":authority")

    val fixedPseudoHeaders = LinkedHashMap<String, String>().apply {
        orderedPseudoHeaderNames.forEach { name ->
            val value = pseudoHeaders[name.removePrefix(":")] ?: pseudoHeaders[name]
            if (value != null) {
                put(name, value)
            }
        }

        pseudoHeaders.forEach { (key, value) ->
            val properKey = if (key.startsWith(":")) key else ":$key"
            if (!containsKey(properKey)) {
                put(properKey, value)
            }
        }
    }

    return (fixedPseudoHeaders + headers).map { HttpHeader.httpHeader(it.key.lowercase(), it.value) }
}

/**
 * Normalizes HTTP request line endings from MCP clients.
 *
 * MCP clients (e.g. Claude Code) often emit `\r\n` as the 4-character literal
 * sequence backslash-r-backslash-n in JSON tool parameters rather than actual
 * CR (0x0D) + LF (0x0A) bytes. The resulting text parses as a single line,
 * which strict servers (e.g. Apache-Coyote) reject with 400 Bad Request and
 * which Burp/Montoya may "repair" by injecting headers after the body
 * separator.
 *
 * Normalization is applied only to the request prelude (request line and
 * headers, up to and including the first blank line). The body is preserved
 * verbatim so that legitimate escape sequences in bodies — e.g. `\n` inside a
 * JSON string literal — and binary payloads remain byte-exact. If no blank
 * line is present, the entire content is treated as prelude.
 */
internal fun normalizeHttpContent(content: String): String {
    val preludeEnd = findPreludeEnd(content) ?: return normalizePrelude(content)
    return normalizePrelude(content.substring(0, preludeEnd)) + content.substring(preludeEnd)
}

private val BLANK_LINE_MARKERS = listOf(
    "\r\n\r\n",         // actual CRLF blank line
    "\n\n",              // actual LF blank line
    "\\r\\n\\r\\n",     // literal CRLF blank line
    "\\n\\n",            // literal LF blank line
)

private fun findPreludeEnd(content: String): Int? {
    var bestStart = -1
    var bestLen = 0
    for (marker in BLANK_LINE_MARKERS) {
        val idx = content.indexOf(marker)
        if (idx >= 0 && (bestStart < 0 || idx < bestStart)) {
            bestStart = idx
            bestLen = marker.length
        }
    }
    return if (bestStart < 0) null else bestStart + bestLen
}

private fun normalizePrelude(prelude: String): String = prelude
    .replace("\\r\\n", "\n")   // Literal \r\n escape sequences → LF
    .replace("\\n", "\n")      // Remaining literal \n → LF
    .replace("\\r", "")        // Remaining literal \r → remove
    .replace("\r", "")          // Actual CR → remove
    .replace("\n", "\r\n")      // All LF → proper CRLF

// ---------------------------------------------------------------------------
// Filter extension functions — applied before serialisation so we only encode
// items the caller actually wants.
// ---------------------------------------------------------------------------

private fun ProxyHttpRequestResponse.matchesFilter(
    inScopeOnly: Boolean?,
    hosts: List<String>?,
    methods: List<String>?,
    statusCodes: List<Int>?,
    excludeExtensions: List<String>?,
    mimeTypes: List<String>?,
    highlightColor: String?,
    hasHighlight: Boolean?,
    editedOnly: Boolean?,
    hasNotes: Boolean?,
    pathContains: String?,
): Boolean {
    if (inScopeOnly == true && !request().isInScope()) return false
    if (!hosts.isNullOrEmpty() && !hosts.any { it.equals(request().httpService().host(), ignoreCase = true) }) return false
    if (!methods.isNullOrEmpty() && !methods.any { it.equals(request().method(), ignoreCase = true) }) return false
    if (!statusCodes.isNullOrEmpty()) {
        if (!hasResponse()) return false
        val sc = response()?.statusCode()?.toInt() ?: return false
        if (!statusCodes.contains(sc)) return false
    }
    if (!excludeExtensions.isNullOrEmpty()) {
        val ext = request().fileExtension()
        if (ext.isNotEmpty() && excludeExtensions.any { it.equals(ext, ignoreCase = true) }) return false
    }
    if (!mimeTypes.isNullOrEmpty() && !mimeTypes.any { it.equals(mimeType().name, ignoreCase = true) }) return false
    if (highlightColor != null && !annotations().highlightColor().name.equals(highlightColor, ignoreCase = true)) return false
    if (hasHighlight != null && annotations().hasHighlightColor() != hasHighlight) return false
    if (editedOnly == true && !edited()) return false
    if (hasNotes != null && annotations().hasNotes() != hasNotes) return false
    if (pathContains != null && !request().path().contains(pathContains, ignoreCase = true)) return false
    return true
}

private fun OrganizerItem.matchesFilter(
    hosts: List<String>?,
    methods: List<String>?,
    statusCodes: List<Int>?,
    highlightColor: String?,
    hasHighlight: Boolean?,
    hasNotes: Boolean?,
    pathContains: String?,
): Boolean {
    if (!hosts.isNullOrEmpty() && !hosts.any { it.equals(request()?.httpService()?.host(), ignoreCase = true) }) return false
    if (!methods.isNullOrEmpty() && !methods.any { it.equals(request()?.method(), ignoreCase = true) }) return false
    if (!statusCodes.isNullOrEmpty()) {
        val sc = response()?.statusCode()?.toInt() ?: return false
        if (!statusCodes.contains(sc)) return false
    }
    if (highlightColor != null && !annotations().highlightColor().name.equals(highlightColor, ignoreCase = true)) return false
    if (hasHighlight != null && annotations().hasHighlightColor() != hasHighlight) return false
    if (hasNotes != null && annotations().hasNotes() != hasNotes) return false
    if (pathContains != null && request()?.path()?.contains(pathContains, ignoreCase = true) != true) return false
    return true
}

private fun ProxyWebSocketMessage.matchesFilter(
    hosts: List<String>?,
    directionFilter: String?,
    highlightColor: String?,
    hasHighlight: Boolean?,
    hasNotes: Boolean?,
): Boolean {
    if (!hosts.isNullOrEmpty() && !hosts.any { it.equals(upgradeRequest().httpService().host(), ignoreCase = true) }) return false
    if (directionFilter != null && !direction().name.equals(directionFilter, ignoreCase = true)) return false
    if (highlightColor != null && !annotations().highlightColor().name.equals(highlightColor, ignoreCase = true)) return false
    if (hasHighlight != null && annotations().hasHighlightColor() != hasHighlight) return false
    if (hasNotes != null && annotations().hasNotes() != hasNotes) return false
    return true
}

// ---------------------------------------------------------------------------
// Tool registration
// ---------------------------------------------------------------------------

fun Server.registerTools(api: MontoyaApi, config: McpConfig) {

    mcpTool<SendHttp1Request>("Sends a raw HTTP/1.1 request to the target and returns the raw response, or '<no response>' if none arrived. This sends real traffic. To inspect a request without sending it, use create_repeater_tab instead.") {
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, content, api)
        }
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/1.1 request: $targetHostname:$targetPort")

        val fixedContent = normalizeHttpContent(content)

        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        val response = api.http().sendRequest(request)

        response?.toString() ?: "<no response>"
    }

    mcpTool<SendHttp2Request>("Sends an HTTP/2 request to the target and returns the raw response. This sends real traffic. Put :method, :path, :scheme and :authority in pseudoHeaders, regular headers in headers, and only the message body in requestBody.") {
        val http2RequestDisplay = buildString {
            pseudoHeaders.forEach { (key, value) ->
                val headerName = if (key.startsWith(":")) key else ":$key"
                appendLine("$headerName: $value")
            }
            headers.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            if (requestBody.isNotBlank()) {
                appendLine()
                append(requestBody)
            }
        }

        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, http2RequestDisplay, api)
        }
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/2 request: $targetHostname:$targetPort")

        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)

        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        val response = api.http().sendRequest(request, HttpMode.HTTP_2)

        response?.toString() ?: "<no response>"
    }

    mcpUnitTool<CreateRepeaterTab>("Opens a new HTTP/1.1 Repeater tab containing the given raw request. The request is not sent; the user sends it from Repeater. Use CRLF line endings. Prefer create_repeater_tab_http2 for modern web targets that speak HTTP/2.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpUnitTool<CreateRepeaterTabHttp2>("Opens a new HTTP/2 Repeater tab containing the given request. The request is not sent; the user sends it from Repeater. Use this by default for modern web targets.") {
        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)
        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpUnitTool<SendToIntruder>("Opens a new Intruder tab with the given raw HTTP/1.1 request. No attack is started; the user sets payload positions and starts the attack in Burp. Use CRLF line endings.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        api.intruder().sendToIntruder(request, tabName)
    }

    mcpTool<UrlEncode>("URL-encodes (percent-encodes) the input text and returns the result.") {
        api.utilities().urlUtils().encode(content)
    }

    mcpTool<UrlDecode>("URL-decodes the input text and returns the result.") {
        api.utilities().urlUtils().decode(content)
    }

    mcpTool<Base64Encode>("Base64-encodes the input text and returns the result.") {
        api.utilities().base64Utils().encodeToString(content)
    }

    mcpTool<Base64Decode>("Base64-decodes the input text and returns the result as text.") {
        api.utilities().base64Utils().decode(content).toString()
    }

    mcpTool<GenerateRandomString>("Generates a random string of the given length, using only the given characters.") {
        api.utilities().randomUtils().randomString(length, characterSet)
    }

    mcpTool(
        "output_project_options",
        "Returns the current project-level configuration as JSON. Call this before set_project_options to see the structure and option names. Credentials may be filtered out, depending on the user's settings."
    ) {
        val json = api.burpSuite().exportProjectOptionsAsJson()
        if (config.filterConfigCredentials) {
            filterConfigCredentials(json)
        } else {
            json
        }
    }

    mcpTool(
        "output_user_options",
        "Returns the current user-level configuration as JSON. Call this before set_user_options to see the structure and option names. Credentials may be filtered out, depending on the user's settings."
    ) {
        val json = api.burpSuite().exportUserOptionsAsJson()
        if (config.filterConfigCredentials) {
            filterConfigCredentials(json)
        } else {
            json
        }
    }

    val toolingDisabledMessage =
        "User has disabled configuration editing. They can enable it in the MCP tab in Burp by selecting 'Enable tools that can edit your config'"

    mcpTool<SetProjectOptions>("Merges the given JSON into the project-level configuration. Export first with output_project_options to see the structure, and make sure the JSON has a top-level 'project_options' object. Only works when the user has enabled 'Enable tools that can edit your config'.") {
        if (config.configEditingTooling) {
            api.logging().logToOutput("Setting project-level configuration: $json")
            api.burpSuite().importProjectOptionsFromJson(json)

            "Project configuration has been applied"
        } else {
            toolingDisabledMessage
        }
    }

    mcpTool<SetUserOptions>("Merges the given JSON into the user-level configuration. Export first with output_user_options to see the structure, and make sure the JSON has a top-level 'user_options' object. Only works when the user has enabled 'Enable tools that can edit your config'.") {
        if (config.configEditingTooling) {
            api.logging().logToOutput("Setting user-level configuration: $json")
            api.burpSuite().importUserOptionsFromJson(json)

            "User configuration has been applied"
        } else {
            toolingDisabledMessage
        }
    }

    if (api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
        mcpPaginatedTool<GetScannerIssues, AuditIssue>(
            "Returns issues found by Burp's scanner as a JSON envelope with pagination. Burp Suite Professional only. Call get_scanner_issue_count first to see how many there are. count is capped at 100 per call.",
            select = { api.siteMap().issues() },
            encode = { Json.encodeToString(it.toSerializableForm()) }
        )

        mcpTool(
            "get_scanner_issue_count",
            "Returns how many issues Burp's scanner has found. Burp Suite Professional only."
        ) {
            api.siteMap().issues().size.toString()
        }

        val collaboratorClient by lazy { api.collaborator().createClient() }

        mcpTool<GenerateCollaboratorPayload>(
            "Generates a Burp Collaborator payload for out-of-band (OOB) testing. Burp Suite Professional only. " +
            "Inject the payload into requests to detect server-side interactions such as DNS lookups, HTTP requests and SMTP. " +
            "Returns the payload, its payload ID and the Collaborator server address. Use get_collaborator_interactions with the payload ID to check for hits."
        ) {
            api.logging().logToOutput("MCP generating Collaborator payload${customData?.let { " with custom data" } ?: ""}")

            val payload = if (customData != null) {
                collaboratorClient.generatePayload(customData)
            } else {
                collaboratorClient.generatePayload()
            }

            val server = collaboratorClient.server()
            "Payload: $payload\nPayload ID: ${payload.id()}\nCollaborator server: ${server.address()}"
        }

        mcpTool<GetCollaboratorInteractions>(
            "Polls Burp Collaborator for out-of-band interactions (DNS, HTTP, SMTP). Burp Suite Professional only. " +
            "Optionally filter by payloadId from generate_collaborator_payload. " +
            "Returns 'No interactions detected' if nothing has arrived yet; the target may need time to call back, so poll again later. Otherwise returns each interaction's type, timestamp, client IP and protocol details."
        ) {
            api.logging().logToOutput("MCP polling Collaborator interactions${payloadId?.let { " for payload: $it" } ?: ""}")

            val interactions = if (payloadId != null) {
                collaboratorClient.getInteractions(InteractionFilter.interactionIdFilter(payloadId))
            } else {
                collaboratorClient.getAllInteractions()
            }

            if (interactions.isEmpty()) {
                "No interactions detected"
            } else {
                interactions.joinToString("\n\n") {
                    Json.encodeToString(it.toSerializableForm())
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Proxy HTTP history
    // -----------------------------------------------------------------------

    mcpTool(
        "get_proxy_history_count",
        "Returns how many items are in the proxy HTTP history. Call this first on a large history to plan your paging."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) return@mcpTool "HTTP history access denied by Burp Suite"
        api.proxy().history().size.toString()
    }

    mcpPaginatedTool<GetProxyHttpHistory, ProxyHttpRequestResponse>(
        "Returns proxy HTTP history items as a JSON envelope: total, returned, offset, nextOffset (absent on the last page) and items. " +
        "Newest items come first by default. Use count and offset to page, and use the filters to narrow results before paging. " +
        "Set headersOnly=true to scan without bodies. count is capped at 100 per call. Items with _truncated:true were cut to fit maxItemLength.",
        select = {
            requireDataAccess(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
            api.proxy().history()
                .let { list -> if (newestFirst != false) list.asReversed() else list }
                .filter { it.matchesFilter(inScopeOnly, hosts, methods, statusCodes, excludeExtensions, mimeTypes, highlightColor, hasHighlight, editedOnly, hasNotes, pathContains) }
        },
        encode = { encodeHistoryItem(it.toSerializableForm(headersOnly == true), maxItemLength) }
    )

    mcpPaginatedTool<GetProxyHttpHistoryRegex, ProxyHttpRequestResponse>(
        "Returns proxy HTTP history items whose raw request or response text matches a Java regular expression. " +
        "Same JSON envelope and filters as get_proxy_http_history. The regex runs only on items that pass the other filters, so add filters on large histories. " +
        "count is capped at 100 per call.",
        select = {
            requireDataAccess(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
            val flags = if (caseInsensitive == true) Pattern.CASE_INSENSITIVE else 0
            val compiledRegex = Pattern.compile(regex, flags)

            api.proxy().history()
                .let { list -> if (newestFirst != false) list.asReversed() else list }
                // Cheap metadata filters first, so the regex scans only the items that remain.
                .filter { it.matchesFilter(inScopeOnly, hosts, methods, statusCodes, excludeExtensions, mimeTypes, highlightColor, hasHighlight, editedOnly, hasNotes, pathContains) }
                .filter { it.contains(compiledRegex) }
        },
        encode = { encodeHistoryItem(it.toSerializableForm(headersOnly == true), maxItemLength) }
    )

    // -----------------------------------------------------------------------
    // Organizer
    // -----------------------------------------------------------------------

    mcpTool(
        "get_organizer_count",
        "Returns how many items are in Burp's Organizer tab. Call this first to plan your paging."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        }
        if (!allowed) return@mcpTool "Organizer access denied by Burp Suite"
        api.organizer().items().size.toString()
    }

    mcpPaginatedTool<GetOrganizerItems, OrganizerItem>(
        "Returns Organizer items (requests the user has sent to the Organizer) as a JSON envelope, newest first by default. " +
        "Use count and offset to page, and the filters to narrow results. count is capped at 100 per call.",
        select = {
            requireDataAccess(DataAccessType.ORGANIZER, config, api, "Organizer")
            api.organizer().items()
                .let { list -> if (newestFirst != false) list.asReversed() else list }
                .filter { it.matchesFilter(hosts, methods, statusCodes, highlightColor, hasHighlight, hasNotes, pathContains) }
        },
        encode = { encodeHistoryItem(it.toSerializableForm(headersOnly == true), maxItemLength) }
    )

    mcpPaginatedTool<GetOrganizerItemsRegex, OrganizerItem>(
        "Returns Organizer items whose raw request or response text matches a Java regular expression. " +
        "Same JSON envelope and filters as get_organizer_items. count is capped at 100 per call.",
        select = {
            requireDataAccess(DataAccessType.ORGANIZER, config, api, "Organizer")
            val flags = if (caseInsensitive == true) Pattern.CASE_INSENSITIVE else 0
            val compiledRegex = Pattern.compile(regex, flags)

            api.organizer().items()
                .let { list -> if (newestFirst != false) list.asReversed() else list }
                // Cheap metadata filters first, so the regex scans only the items that remain.
                .filter { it.matchesFilter(hosts, methods, statusCodes, highlightColor, hasHighlight, hasNotes, pathContains) }
                .filter { it.contains(compiledRegex) }
        },
        encode = { encodeHistoryItem(it.toSerializableForm(headersOnly == true), maxItemLength) }
    )

    // -----------------------------------------------------------------------
    // Proxy WebSocket history
    // -----------------------------------------------------------------------

    mcpTool(
        "get_websocket_history_count",
        "Returns how many messages are in the proxy WebSocket history. Call this first to plan your paging."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        }
        if (!allowed) return@mcpTool "WebSocket history access denied by Burp Suite"
        api.proxy().webSocketHistory().size.toString()
    }

    mcpPaginatedTool<GetProxyWebsocketHistory, ProxyWebSocketMessage>(
        "Returns proxy WebSocket messages as a JSON envelope, newest first by default. " +
        "Use direction to choose client or server messages, and hosts to choose connections. count is capped at 100 per call.",
        select = {
            requireDataAccess(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
            api.proxy().webSocketHistory()
                .let { list -> if (newestFirst != false) list.asReversed() else list }
                .filter { it.matchesFilter(hosts, direction, highlightColor, hasHighlight, hasNotes) }
        },
        encode = { encodeHistoryItem(it.toSerializableForm(), maxItemLength) }
    )

    mcpPaginatedTool<GetProxyWebsocketHistoryRegex, ProxyWebSocketMessage>(
        "Returns proxy WebSocket messages whose content matches a Java regular expression. " +
        "Same JSON envelope and filters as get_proxy_websocket_history. count is capped at 100 per call.",
        select = {
            requireDataAccess(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
            val flags = if (caseInsensitive == true) Pattern.CASE_INSENSITIVE else 0
            val compiledRegex = Pattern.compile(regex, flags)

            api.proxy().webSocketHistory()
                .let { list -> if (newestFirst != false) list.asReversed() else list }
                // Cheap metadata filters first, so the regex scans only the items that remain.
                .filter { it.matchesFilter(hosts, direction, highlightColor, hasHighlight, hasNotes) }
                .filter { it.contains(compiledRegex) }
        },
        encode = { encodeHistoryItem(it.toSerializableForm(), maxItemLength) }
    )

    mcpTool<SetTaskExecutionEngineState>("Pauses or resumes Burp's task execution engine, which runs background work such as scans. Pass running=true to resume it and running=false to pause it.") {
        api.burpSuite().taskExecutionEngine().state = if (running) RUNNING else PAUSED

        "Task execution engine is now ${if (running) "running" else "paused"}"
    }

    mcpTool<SetProxyInterceptState>("Turns Burp Proxy interception on or off. While interception is on, proxied requests wait in Burp until the user acts on them, which can block traffic.") {
        if (intercepting) {
            api.proxy().enableIntercept()
        } else {
            api.proxy().disableIntercept()
        }

        "Intercept has been ${if (intercepting) "enabled" else "disabled"}"
    }

    mcpTool("get_active_editor_contents", "Returns the text of the Burp editor that currently has keyboard focus. Returns '<No active editor>' if no Burp text area has focus; ask the user to click into the editor first.") {
        getActiveEditor(api)?.text ?: "<No active editor>"
    }

    mcpTool<SetActiveEditorContents>("Replaces the text of the Burp editor that currently has focus. Returns '<No active editor>' or '<Current editor is not editable>' if that is not possible.") {
        val editor = getActiveEditor(api) ?: return@mcpTool "<No active editor>"

        if (!editor.isEditable) {
            return@mcpTool "<Current editor is not editable>"
        }

        editor.text = text

        "Editor text has been set"
    }
}

fun getActiveEditor(api: MontoyaApi): JTextArea? {
    val frame = api.userInterface().swingUtils().suiteFrame()

    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val permanentFocusOwner = focusManager.permanentFocusOwner

    val isInBurpWindow = generateSequence(permanentFocusOwner) { it.parent }.any { it == frame }

    return if (isInBurpWindow && permanentFocusOwner is JTextArea) {
        permanentFocusOwner
    } else {
        null
    }
}
