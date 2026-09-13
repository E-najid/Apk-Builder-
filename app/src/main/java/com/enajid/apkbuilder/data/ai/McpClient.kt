package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal MCP client over the Streamable HTTP transport: initialize +
 * tools/list + tools/call, one JSON-RPC POST per call. The session id is
 * captured at initialize and replayed on subsequent requests.
 */
class McpClient(private val endpoint: String, private val token: String) {

    private var sessionId: String? = null
    private val nextId = AtomicLong(1)

    /** Handshake: initialize + notifications/initialized. */
    suspend fun initialize() {
        val id = nextId.getAndIncrement()
        callRpc(id, McpProtocol.initializeRequest(id))
        AiDebugLog.ok("mcp", "✓ initialized $endpoint")
        notify(McpProtocol.initializedNotification())
    }

    /** All tools the server offers (follows pagination, capped at 200). */
    suspend fun listTools(): List<McpTool> {
        var cursor: String? = null
        val all = mutableListOf<McpTool>()
        do {
            val id = nextId.getAndIncrement()
            val result = callRpc(id, McpProtocol.listToolsRequest(id, cursor))
            val (tools, next) = McpProtocol.parseToolList(result)
            all += tools
            cursor = next
        } while (cursor != null && all.size < 200)
        return all
    }

    /** Runs a tool; returns (text, isError). */
    suspend fun callTool(name: String, argumentsJson: String): Pair<String, Boolean> {
        val id = nextId.getAndIncrement()
        val result = callRpc(id, McpProtocol.callToolRequest(id, name, argumentsJson))
        return McpProtocol.parseToolCallResult(result)
    }

    // ---------------------------------------------------------------- http

    private suspend fun callRpc(id: Long, bodyText: String): JsonElement {
        val response = post(bodyText)
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                AiDebugLog.warn("mcp", "← HTTP ${it.code} ($endpoint)", details = text.take(400))
                throw AiException(
                    when (it.code) {
                        401, 403 -> "MCP server টোকেন মেনে নেয়নি (HTTP ${it.code})"
                        404 -> "এই URL-এ MCP server নেই (HTTP 404) — URL দেখো"
                        else -> "MCP server error (HTTP ${it.code})"
                    },
                )
            }
            val session = it.header("Mcp-Session-Id")
            if (session != null) sessionId = session
            return when (val parsed = McpProtocol.findResponse(text, id)) {
                is McpProtocol.RpcResponse.Ok -> parsed.result
                is McpProtocol.RpcResponse.Err -> throw AiException("MCP: ${parsed.message}")
                McpProtocol.RpcResponse.NotOurs -> throw AiException("MCP server উত্তর দিলো না")
            }
        }
    }

    private suspend fun notify(bodyText: String) {
        try {
            post(bodyText).close() // 202/204 — notifications have no response body
        } catch (e: AiException) {
            // A server that rejects the notification is still usable.
            AiDebugLog.warn("mcp", "initialized notification ব্যর্থ: ${e.message}")
        }
    }

    private suspend fun post(bodyText: String): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        token.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        builder.post(bodyText.toRequestBody("application/json".toMediaType()))
        try {
            client.newCall(builder.build()).await()
        } catch (e: IOException) {
            throw AiException("MCP server-এ পৌঁছানো যাচ্ছে না ($endpoint) — internet ও URL দেখো।")
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
