package com.enajid.apkbuilder.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A tool the phone-hosted MCP server exposes to Claude / ChatGPT. */
data class PhoneMcpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Result of dispatching one JSON-RPC POST body. */
data class McpDispatchResult(
    /** HTTP status: 200 for responses, 202 for accepted notifications. */
    val status: Int,
    /** JSON-RPC response body; null for notifications. */
    val body: String?,
    /** Session id to advertise on the initialize response. */
    val sessionId: String? = null,
)

/**
 * Pure, Android-free JSON-RPC 2.0 / MCP dispatcher for the phone-hosted
 * server (see [McpHost]). Handles initialize, ping, tools/list and
 * tools/call; everything else answers with the proper JSON-RPC error.
 * HTTP wiring lives in the NanoHTTPD layer.
 */
class PhoneMcpDispatcher(
    private val serverName: String = "APK Builder",
    private val serverVersion: String = "1.0",
    private val tools: List<PhoneMcpTool>,
    private val callTool: suspend (name: String, argsJson: String) -> Pair<String, Boolean>,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(body: String): McpDispatchResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return respondError(jsonRpcError(-32700, "parse error: invalid JSON"), id = null)
        val id: JsonElement? = root["id"] as? JsonPrimitive
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull

        // No id -> notification (notifications/initialized and friends).
        if (id == null) {
            return McpDispatchResult(status = 202, body = null)
        }
        if (method == null) {
            return respondError(jsonRpcError(-32600, "invalid request: missing method"), id)
        }

        return when (method) {
            "initialize" -> {
                val requested = (root["params"] as? JsonObject)
                    ?.get("protocolVersion")?.let { (it as? JsonPrimitive)?.contentOrNull }
                val result = buildJsonObject {
                    put("protocolVersion", requested?.takeIf { it.isNotBlank() } ?: McpProtocol.PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", serverName)
                            put("version", serverVersion)
                        },
                    )
                }
                respond(result, id, sessionId = newSessionId())
            }
            "ping" -> respond(buildJsonObject { }, id)
            "tools/list" -> respond(
                buildJsonObject {
                    put(
                        "tools",
                        buildJsonArray {
                            tools.forEach { tool ->
                                add(
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("inputSchema", tool.inputSchema)
                                    },
                                )
                            }
                        },
                    )
                },
                id,
            )
            "tools/call" -> {
                val params = root["params"] as? JsonObject
                val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
                if (name == null || tools.none { it.name == name }) {
                    return respondError(jsonRpcError(-32602, "unknown tool: ${name ?: "(none)"}"), id)
                }
                val arguments = params?.get("arguments")?.toString() ?: "{}"
                val (text, isError) = callTool(name, arguments)
                respond(
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                        put("isError", isError)
                    },
                    id,
                )
            }
            else -> respondError(jsonRpcError(-32601, "method not found: $method"), id)
        }
    }

    private fun respond(result: JsonElement, id: JsonElement?, sessionId: String? = null) =
        McpDispatchResult(
            status = 200,
            body = buildJsonObject {
                put("jsonrpc", "2.0")
                id?.let { put("id", it) }
                put("result", result)
            }.toString(),
            sessionId = sessionId,
        )

    private fun respondError(error: JsonElement, id: JsonElement?) =
        McpDispatchResult(
            status = 200,
            body = buildJsonObject {
                put("jsonrpc", "2.0")
                id?.let { put("id", it) }
                put("error", error)
            }.toString(),
        )

    private fun jsonRpcError(code: Int, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }

    private fun newSessionId(): String =
        java.util.UUID.randomUUID().toString()
}
