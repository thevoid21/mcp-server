package net.portswigger.mcp.tools

import burp.api.montoya.http.HttpService
import kotlinx.serialization.Serializable
import net.portswigger.mcp.schema.ParamDoc

// ---------------------------------------------------------------------------
// Tool input classes. Each property is sent to MCP clients as its JSON schema
// type plus the @ParamDoc text. Keep the text accurate: it is the only guidance
// a model gets about a parameter. See README "Tool documentation".
// ---------------------------------------------------------------------------

interface HttpServiceParams {
    @ParamDoc("Hostname or IP address of the server to connect to, for example \"example.com\" or \"10.0.0.5\".")
    val targetHostname: String

    @ParamDoc("TCP port of the server, for example 443 for HTTPS or 80 for HTTP.")
    val targetPort: Int

    @ParamDoc("true to connect over TLS (HTTPS), false for plain HTTP.")
    val usesHttps: Boolean

    fun toMontoyaService(): HttpService = HttpService.httpService(targetHostname, targetPort, usesHttps)
}

private const val RAW_HTTP1_CONTENT_DOC =
    "The complete raw HTTP/1.1 request: the request line, the headers, a blank line, then the body. " +
    "Use CRLF line endings. Literal backslash-r-backslash-n sequences in the header section are converted to CRLF; the body is sent unchanged."

private const val HTTP2_PSEUDO_HEADERS_DOC =
    "HTTP/2 pseudo-headers as a name-to-value map. Use ':method', ':path', ':scheme' and ':authority' (the leading colon is optional). " +
    "Put regular headers in 'headers', not here."

private const val HTTP2_HEADERS_DOC =
    "Regular HTTP/2 headers as a name-to-value map, for example {\"content-type\": \"application/json\"}. " +
    "Do not include pseudo-headers. Names are sent in lower case."

private const val HTTP2_BODY_DOC =
    "The message body as a string. Use an empty string for requests without a body. Do not put headers in this field."

@Serializable
data class SendHttp1Request(
    @ParamDoc(RAW_HTTP1_CONTENT_DOC)
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class SendHttp2Request(
    @ParamDoc(HTTP2_PSEUDO_HEADERS_DOC)
    val pseudoHeaders: Map<String, String>,
    @ParamDoc(HTTP2_HEADERS_DOC)
    val headers: Map<String, String>,
    @ParamDoc(HTTP2_BODY_DOC)
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class CreateRepeaterTab(
    @ParamDoc("Optional name for the new Repeater tab. Omit it or pass null to let Burp name the tab.")
    val tabName: String?,
    @ParamDoc(RAW_HTTP1_CONTENT_DOC + " The tab is created with this request; the request is not sent.")
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class CreateRepeaterTabHttp2(
    @ParamDoc("Optional name for the new Repeater tab. Omit it or pass null to let Burp name the tab.")
    val tabName: String?,
    @ParamDoc(HTTP2_PSEUDO_HEADERS_DOC)
    val pseudoHeaders: Map<String, String>,
    @ParamDoc(HTTP2_HEADERS_DOC)
    val headers: Map<String, String>,
    @ParamDoc(HTTP2_BODY_DOC + " The tab is created with this request; the request is not sent.")
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class SendToIntruder(
    @ParamDoc("Optional name for the new Intruder tab. Omit it or pass null to let Burp name the tab.")
    val tabName: String?,
    @ParamDoc(RAW_HTTP1_CONTENT_DOC + " The request is loaded into Intruder; no attack is started.")
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class UrlEncode(
    @ParamDoc("The text to URL-encode (percent-encode).")
    val content: String
)

@Serializable
data class UrlDecode(
    @ParamDoc("The URL-encoded text to decode.")
    val content: String
)

@Serializable
data class Base64Encode(
    @ParamDoc("The text to Base64-encode.")
    val content: String
)

@Serializable
data class Base64Decode(
    @ParamDoc("The Base64 text to decode. The result is returned as text, so binary data will not display cleanly.")
    val content: String
)

@Serializable
data class GenerateRandomString(
    @ParamDoc("Number of characters to generate, for example 16.")
    val length: Int,
    @ParamDoc("The characters to choose from, for example \"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789\".")
    val characterSet: String
)

@Serializable
data class SetProjectOptions(
    @ParamDoc("Project-level settings to merge into the current configuration, as a JSON string. The top level must be an object with a 'project_options' key. Call output_project_options first to see the current structure.")
    val json: String
)

@Serializable
data class SetUserOptions(
    @ParamDoc("User-level settings to merge into the current configuration, as a JSON string. The top level must be an object with a 'user_options' key. Call output_user_options first to see the current structure.")
    val json: String
)

@Serializable
data class SetTaskExecutionEngineState(
    @ParamDoc("true to resume Burp's task execution engine, false to pause it.")
    val running: Boolean
)

@Serializable
data class SetProxyInterceptState(
    @ParamDoc("true to turn Proxy interception on, so new proxied requests wait for the user to act on them in Burp. false to turn it off.")
    val intercepting: Boolean
)

@Serializable
data class SetActiveEditorContents(
    @ParamDoc("The new text for the focused editor. It replaces the editor's current contents.")
    val text: String
)

@Serializable
data class GetScannerIssues(override val count: Int, override val offset: Int) : Paginated

// ---------------------------------------------------------------------------
// History and Organizer input classes. Filter fields are all nullable (optional).
// ---------------------------------------------------------------------------

private const val NEWEST_FIRST_DOC =
    "true (the default) returns the newest items first. false returns the oldest items first."

private const val HOSTS_DOC =
    "Only items whose hostname is in this list, for example [\"api.example.com\"]. Matching ignores case."

private const val METHODS_DOC =
    "Only items with one of these HTTP methods, for example [\"POST\", \"PUT\"]. Matching ignores case."

private const val STATUS_CODES_DOC =
    "Only items whose response has one of these status codes, for example [200, 401, 403]. Items without a response are left out."

private const val HIGHLIGHT_COLOR_DOC =
    "Only items with this highlight colour: RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PINK, MAGENTA or GRAY. Matching ignores case."

private const val HAS_HIGHLIGHT_DOC =
    "true returns only highlighted items. false returns only items without a highlight."

private const val HAS_NOTES_DOC =
    "true returns only items that have an annotation note. false returns only items without one."

private const val PATH_CONTAINS_DOC =
    "Only items whose request path contains this text, ignoring case, for example \"/api/v2/\"."

private const val HEADERS_ONLY_DOC =
    "true returns only the request and response headers and replaces each body with '<body omitted>'. Use it when scanning many items to save context."

private const val MAX_ITEM_LENGTH_DOC =
    "Maximum characters per item before it is cut short. The default is 5000. Raise it for large cookies or bodies. Cut items have \"_truncated\": true."

private const val HTTP_REGEX_DOC =
    "Java regular expression to search for in each item's raw request and response text, for example \"password\\s*=\"."

private const val CASE_INSENSITIVE_DOC =
    "true to match the regex ignoring case. The default is false."

@Serializable
data class GetProxyHttpHistory(
    override val count: Int,
    override val offset: Int,
    @ParamDoc(NEWEST_FIRST_DOC)
    val newestFirst: Boolean? = null,
    @ParamDoc("true returns only items that are in Burp's target scope.")
    val inScopeOnly: Boolean? = null,
    @ParamDoc(HOSTS_DOC)
    val hosts: List<String>? = null,
    @ParamDoc(METHODS_DOC)
    val methods: List<String>? = null,
    @ParamDoc(STATUS_CODES_DOC)
    val statusCodes: List<Int>? = null,
    @ParamDoc("Leave out items whose URL file extension is in this list, for example [\"png\", \"css\", \"woff2\"].")
    val excludeExtensions: List<String>? = null,
    @ParamDoc("Only items whose MIME type is in this list, using Burp's MIME type names, for example [\"JSON\", \"HTML\", \"SCRIPT\"].")
    val mimeTypes: List<String>? = null,
    @ParamDoc(HIGHLIGHT_COLOR_DOC)
    val highlightColor: String? = null,
    @ParamDoc(HAS_HIGHLIGHT_DOC)
    val hasHighlight: Boolean? = null,
    @ParamDoc("true returns only items changed by a match-and-replace rule.")
    val editedOnly: Boolean? = null,
    @ParamDoc(HAS_NOTES_DOC)
    val hasNotes: Boolean? = null,
    @ParamDoc(PATH_CONTAINS_DOC)
    val pathContains: String? = null,
    @ParamDoc(HEADERS_ONLY_DOC)
    val headersOnly: Boolean? = null,
    @ParamDoc(MAX_ITEM_LENGTH_DOC)
    val maxItemLength: Int? = null,
) : Paginated

@Serializable
data class GetProxyHttpHistoryRegex(
    @ParamDoc(HTTP_REGEX_DOC)
    val regex: String,
    override val count: Int,
    override val offset: Int,
    @ParamDoc(CASE_INSENSITIVE_DOC)
    val caseInsensitive: Boolean? = null,
    @ParamDoc(NEWEST_FIRST_DOC)
    val newestFirst: Boolean? = null,
    @ParamDoc("true returns only items that are in Burp's target scope.")
    val inScopeOnly: Boolean? = null,
    @ParamDoc(HOSTS_DOC)
    val hosts: List<String>? = null,
    @ParamDoc(METHODS_DOC)
    val methods: List<String>? = null,
    @ParamDoc(STATUS_CODES_DOC)
    val statusCodes: List<Int>? = null,
    @ParamDoc("Leave out items whose URL file extension is in this list, for example [\"png\", \"css\", \"woff2\"].")
    val excludeExtensions: List<String>? = null,
    @ParamDoc("Only items whose MIME type is in this list, using Burp's MIME type names, for example [\"JSON\", \"HTML\", \"SCRIPT\"].")
    val mimeTypes: List<String>? = null,
    @ParamDoc(HIGHLIGHT_COLOR_DOC)
    val highlightColor: String? = null,
    @ParamDoc(HAS_HIGHLIGHT_DOC)
    val hasHighlight: Boolean? = null,
    @ParamDoc("true returns only items changed by a match-and-replace rule.")
    val editedOnly: Boolean? = null,
    @ParamDoc(HAS_NOTES_DOC)
    val hasNotes: Boolean? = null,
    @ParamDoc(PATH_CONTAINS_DOC)
    val pathContains: String? = null,
    @ParamDoc(HEADERS_ONLY_DOC)
    val headersOnly: Boolean? = null,
    @ParamDoc(MAX_ITEM_LENGTH_DOC)
    val maxItemLength: Int? = null,
) : Paginated

@Serializable
data class GetOrganizerItems(
    override val count: Int,
    override val offset: Int,
    @ParamDoc(NEWEST_FIRST_DOC)
    val newestFirst: Boolean? = null,
    @ParamDoc(HOSTS_DOC)
    val hosts: List<String>? = null,
    @ParamDoc(METHODS_DOC)
    val methods: List<String>? = null,
    @ParamDoc(STATUS_CODES_DOC)
    val statusCodes: List<Int>? = null,
    @ParamDoc(HIGHLIGHT_COLOR_DOC)
    val highlightColor: String? = null,
    @ParamDoc(HAS_HIGHLIGHT_DOC)
    val hasHighlight: Boolean? = null,
    @ParamDoc(HAS_NOTES_DOC)
    val hasNotes: Boolean? = null,
    @ParamDoc(PATH_CONTAINS_DOC)
    val pathContains: String? = null,
    @ParamDoc(HEADERS_ONLY_DOC)
    val headersOnly: Boolean? = null,
    @ParamDoc(MAX_ITEM_LENGTH_DOC)
    val maxItemLength: Int? = null,
) : Paginated

@Serializable
data class GetOrganizerItemsRegex(
    @ParamDoc(HTTP_REGEX_DOC)
    val regex: String,
    override val count: Int,
    override val offset: Int,
    @ParamDoc(CASE_INSENSITIVE_DOC)
    val caseInsensitive: Boolean? = null,
    @ParamDoc(NEWEST_FIRST_DOC)
    val newestFirst: Boolean? = null,
    @ParamDoc(HOSTS_DOC)
    val hosts: List<String>? = null,
    @ParamDoc(METHODS_DOC)
    val methods: List<String>? = null,
    @ParamDoc(STATUS_CODES_DOC)
    val statusCodes: List<Int>? = null,
    @ParamDoc(HIGHLIGHT_COLOR_DOC)
    val highlightColor: String? = null,
    @ParamDoc(HAS_HIGHLIGHT_DOC)
    val hasHighlight: Boolean? = null,
    @ParamDoc(HAS_NOTES_DOC)
    val hasNotes: Boolean? = null,
    @ParamDoc(PATH_CONTAINS_DOC)
    val pathContains: String? = null,
    @ParamDoc(HEADERS_ONLY_DOC)
    val headersOnly: Boolean? = null,
    @ParamDoc(MAX_ITEM_LENGTH_DOC)
    val maxItemLength: Int? = null,
) : Paginated

@Serializable
data class GetProxyWebsocketHistory(
    override val count: Int,
    override val offset: Int,
    @ParamDoc(NEWEST_FIRST_DOC)
    val newestFirst: Boolean? = null,
    @ParamDoc("Only messages from WebSocket connections whose upgrade request used one of these hostnames, for example [\"app.example.com\"]. Matching ignores case.")
    val hosts: List<String>? = null,
    @ParamDoc("CLIENT_TO_SERVER for messages the client sent, or SERVER_TO_CLIENT for messages the server sent.")
    val direction: String? = null,
    @ParamDoc(HIGHLIGHT_COLOR_DOC)
    val highlightColor: String? = null,
    @ParamDoc(HAS_HIGHLIGHT_DOC)
    val hasHighlight: Boolean? = null,
    @ParamDoc(HAS_NOTES_DOC)
    val hasNotes: Boolean? = null,
    @ParamDoc("Maximum characters per message before it is cut short. The default is 5000. Cut messages have \"_truncated\": true.")
    val maxItemLength: Int? = null,
) : Paginated

@Serializable
data class GetProxyWebsocketHistoryRegex(
    @ParamDoc("Java regular expression to search for in each WebSocket message.")
    val regex: String,
    override val count: Int,
    override val offset: Int,
    @ParamDoc(CASE_INSENSITIVE_DOC)
    val caseInsensitive: Boolean? = null,
    @ParamDoc(NEWEST_FIRST_DOC)
    val newestFirst: Boolean? = null,
    @ParamDoc("Only messages from WebSocket connections whose upgrade request used one of these hostnames, for example [\"app.example.com\"]. Matching ignores case.")
    val hosts: List<String>? = null,
    @ParamDoc("CLIENT_TO_SERVER for messages the client sent, or SERVER_TO_CLIENT for messages the server sent.")
    val direction: String? = null,
    @ParamDoc(HIGHLIGHT_COLOR_DOC)
    val highlightColor: String? = null,
    @ParamDoc(HAS_HIGHLIGHT_DOC)
    val hasHighlight: Boolean? = null,
    @ParamDoc(HAS_NOTES_DOC)
    val hasNotes: Boolean? = null,
    @ParamDoc("Maximum characters per message before it is cut short. The default is 5000. Cut messages have \"_truncated\": true.")
    val maxItemLength: Int? = null,
) : Paginated

@Serializable
data class GenerateCollaboratorPayload(
    @ParamDoc("Optional text to store with the payload, such as a test ID. It is returned with every interaction from this payload, so you can match a hit to the test that caused it.")
    val customData: String? = null
)

@Serializable
data class GetCollaboratorInteractions(
    @ParamDoc("Optional payload ID from generate_collaborator_payload. Leave it out to get interactions for all payloads.")
    val payloadId: String? = null
)
