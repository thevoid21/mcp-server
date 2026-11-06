package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.portswigger.mcp.schema.asInputSchema
import kotlin.experimental.ExperimentalTypeInference

@OptIn(InternalSerializationApi::class)
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    crossinline execute: I.() -> List<ContentBlock>
) {
    val toolName = I::class.simpleName?.toLowerSnakeCase() ?: error("Couldn't find name for ${I::class}")
    val serializer = I::class.serializer()
    val inputSchema = I::class.asInputSchema()

    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, request ->
        try {
            CallToolResult(
                content = execute(
                    Json.decodeFromJsonElement(
                        serializer,
                        request.params.arguments ?: JsonObject(emptyMap())
                    )
                ),
                isError = false
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error: ${e.message}")),
                isError = true
            )
        }
    }

    addTool(name = toolName, description = description, inputSchema = inputSchema, handler = handler)
}

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpToolString")
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    crossinline execute: I.() -> String
) {
    mcpTool<I>(description, execute = {
        listOf(TextContent(execute(this)))
    })
}

inline fun <reified I : Any> Server.mcpUnitTool(
    description: String,
    crossinline execute: I.() -> Unit
) {
    mcpTool<I>(description, execute = {
        execute(this)

        listOf(TextContent("Executed tool"))
    })
}

inline fun <reified I : Paginated, J : Any> Server.mcpPaginatedTool(
    description: String,
    noinline mapper: (J) -> CharSequence = { it.toString() },
    crossinline execute: I.() -> List<J>
) {
    mcpTool<I>(description, execute = {

        val items = execute(this)

        when {
            offset >= items.size -> {
                "Reached end of items"
            }

            else -> {
                val upperLimit = (offset + count).coerceAtMost(items.size)

                items.subList(offset, upperLimit)
                    .joinToString(separator = "\n\n", transform = mapper)
            }
        }
    })
}

/** Largest number of items a paginated tool returns in a single call. */
const val MAX_PAGE_SIZE = 100

/**
 * Registers a paginated tool that filters first and encodes only the requested page.
 *
 * - [select] runs over the whole data set. Keep it cheap: return the matching raw
 *   items (Burp objects), not strings. Its size becomes the envelope `total`.
 * - [encode] runs only for items on the requested page, so its cost is bounded by
 *   `count` (at most [MAX_PAGE_SIZE]) instead of by the size of the history.
 *
 * See README "Large histories" for the cost model.
 */
inline fun <reified I : Paginated, T> Server.mcpPaginatedTool(
    description: String,
    crossinline select: I.() -> List<T>,
    crossinline encode: I.(T) -> String,
) {
    mcpTool<I>(description, execute = {
        listOf(TextContent(paginate(select(this), offset, count) { encode(this, it) }))
    })
}

@PublishedApi
internal fun <T> paginate(
    matches: List<T>,
    offset: Int,
    count: Int,
    encode: (T) -> String,
): String {
    require(offset >= 0) { "offset must be 0 or greater" }
    require(count >= 1) { "count must be at least 1" }

    val total = matches.size
    val start = offset.coerceAtMost(total)
    val end = (start + count.coerceAtMost(MAX_PAGE_SIZE)).coerceAtMost(total)

    val items = JsonArray((start until end).map { index ->
        val encoded = encode(matches[index])
        try { Json.parseToJsonElement(encoded) } catch (_: Exception) { JsonPrimitive(encoded) }
    })
    val envelope = buildJsonObject {
        put("total", total)
        put("returned", items.size)
        put("offset", offset)
        if (end < total) put("nextOffset", end)
        put("items", items)
    }
    return Json.encodeToString(JsonElement.serializer(), envelope)
}

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpNamedToolString")
inline fun Server.mcpTool(
    name: String,
    description: String,
    crossinline execute: () -> List<ContentBlock>
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        try {
            CallToolResult(content = execute(), isError = false)
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }
    addTool(name = name, description = description, inputSchema = ToolSchema(), handler = handler)
}

inline fun Server.mcpTool(
    name: String,
    description: String,
    crossinline execute: () -> String
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        try {
            CallToolResult(content = listOf(TextContent(execute())), isError = false)
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }
    addTool(name = name, description = description, inputSchema = ToolSchema(), handler = handler)
}

fun String.toLowerSnakeCase(): String {
    return this
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z])([A-Z][a-z])"), "$1_$2")
        .replace(Regex("[\\s-]+"), "_")
        .lowercase()
}

interface Paginated {
    val count: Int
    val offset: Int
}
