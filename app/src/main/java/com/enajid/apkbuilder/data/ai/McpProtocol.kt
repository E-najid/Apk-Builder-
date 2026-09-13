package com.enajid.apkbuilder.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A tool offered by an MCP server (JSON-Schema described, like ours). */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Outcome of parsing one JSON-RPC response message. */
sealed interface RpcResponse {
    data class Ok(val result: JsonElement) : RpcResponse
    data class Err(val message: String) : RpcResponse

    /** A well-formed message that isn't the answer we asked for (e.g. a notification). */
    data object NotOurs : RpcResponse
}

/**
 * Pure MCP (Model Context Protocol) wire-format helpers: JSON-RPC 2.0
 * request building and response parsing for the three calls a minimal
 * client needs — `initialize`, `tools/list` and `tools/call` — over the
 * Streamable HTTP transport. HTTP lives in [McpClient].
 */
object McpProtocol {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val PREFIX = "mcp__"

    fun initializeRequest(id: Long): String = rpc(
        id, "initialize",
        buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", "APK Builder")
                    put("version", "1.0")
                },
            )
        },
    )

    fun initializedNotification(): String =
        """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    fun listToolsRequest(id: Long, cursor: String? = null): String = rpc(
        id, "tools/list",
        buildJsonObject { cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) } },
    )

    fun callToolRequest(id: Long, name: String, argumentsJson: String): String {
        val args = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
        return rpc(
            id, "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", (args as? JsonObject) ?: JsonObject(emptyMap()))
            },
        )
    }

    private fun rpc(id: Long, method: String, params: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    /**
     * Parses a JSON-RPC response body (or one SSE payload line) and checks
     * it matches [id]. Notifications (no id) and other ids are NotOurs.
     */
    fun parseResponse(text: String, id: Long): RpcResponse {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return RpcResponse.Err("server sent invalid JSON")
        return respond(obj, id)
    }

    private fun respond(obj: JsonObject, id: Long): RpcResponse {
        val idField = obj["id"] as? JsonPrimitive ?: return RpcResponse.NotOurs
        if (idField.longOrNull != id) return RpcResponse.NotOurs
        obj["error"]?.let { error ->
            val err = error as? JsonObject ?: return RpcResponse.Err("unknown MCP error")
            val code = err["code"]?.jsonPrimitive?.longOrNull
            val message = err["message"]?.jsonPrimitive?.contentOrNull
            return RpcResponse.Err(message ?: "MCP error (code $code)")
        }
        return RpcResponse.Ok(obj["result"] ?: JsonPrimitive("null"))
    }

    /** Extracts the `data:` payloads from an SSE body. */
    fun extractSsePayloads(body: String): List<String> =
        body.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .toList()

    /**
     * Finds the response for [id] in a body that may be plain JSON or an
     * SSE stream with several messages.
     */
    fun findResponse(body: String, id: Long): RpcResponse {
        parseResponse(body, id).let { if (it !is RpcResponse.NotOurs) return it }
        for (payload in extractSsePayloads(body)) {
            parseResponse(payload, id).let { if (it !is RpcResponse.NotOurs) return it }
        }
        return RpcResponse.Err("server উত্তর দিলো না (no response for request $id)")
    }

    /** tools/list result -> tools (+ next page cursor, when paginated). */
    fun parseToolList(result: JsonElement): Pair<List<McpTool>, String?> {
        val obj = result as? JsonObject ?: return emptyList<McpTool>() to null
        val tools = (obj["tools"] as? JsonArray).orEmpty().mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val name = t["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpTool(
                name = name,
                description = t["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = t["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        val cursor = obj["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return tools to cursor
    }

    /** tools/call result -> joined text content + isError flag. */
    fun parseToolCallResult(result: JsonElement): Pair<String, Boolean> {
        val obj = result as? JsonObject ?: return "" to true
        val isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        val text = (obj["content"] as? JsonArray).orEmpty()
            .mapNotNull { el ->
                val c = el as? JsonObject
                if (c?.get("type")?.jsonPrimitive?.contentOrNull == "text") {
                    c["text"]?.jsonPrimitive?.contentOrNull
                } else null
            }
            .joinToString("\n")
        return text to isError
    }

    /** Server name -> slug used in prefixed tool names ("My Tools" -> "mytools"). */
    fun slugFromName(name: String, id: Long): String {
        val slug = name.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(24)
        return slug.ifBlank { "srv$id" }
    }

    fun prefixedToolName(slug: String, tool: String): String = "${PREFIX}${slug}__${tool}"

    /** "mcp__slug__tool" -> ("slug", "tool"), or null when not prefixed. */
    fun splitPrefixed(name: String): Pair<String, String>? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        val idx = rest.indexOf("__")
        if (idx <= 0 || idx == rest.length - 2) return null
        return rest.substring(0, idx) to rest.substring(idx + 2)
    }
}
