package com.enajid.apkbuilder.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Pure mapping between our internal chat DTOs and Anthropic's Messages API
 * wire format (the native Claude protocol). Everything here is plain JSON
 * building/parsing so it stays unit-testable on the JVM; the HTTP side
 * lives in [AnthropicChatClient].
 */
object AnthropicWire {

    const val API_VERSION = "2023-06-01"
    const val MAX_TOKENS = 8192

    /** ChatRequest -> Anthropic POST /messages body (stream flag comes from the request). */
    fun buildBody(request: ChatRequest): JsonObject {
        val system = StringBuilder()
        val messages = mutableListOf<JsonObject>()
        var lastRole: String? = null
        var pendingToolResults = mutableListOf<JsonObject>()

        fun flushToolResults() {
            if (pendingToolResults.isNotEmpty()) {
                messages += buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(pendingToolResults.toList()))
                }
                pendingToolResults = mutableListOf()
                lastRole = "user"
            }
        }

        for (message in request.messages) {
            when (message.role) {
                "system" -> message.content?.let { c ->
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(c)
                }
                "tool" -> pendingToolResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", message.tool_call_id ?: "")
                    put("content", message.content ?: "")
                }
                "user" -> {
                    flushToolResults()
                    val text = message.content.orEmpty()
                    if (lastRole == "user" && messages.isNotEmpty()) {
                        // Anthropic requires alternating roles — merge into one.
                        val last = messages.removeAt(messages.lastIndex)
                        messages += mergeUserText(last, text)
                    } else {
                        messages += buildJsonObject {
                            put("role", "user")
                            put("content", text)
                        }
                        lastRole = "user"
                    }
                }
                "assistant" -> {
                    flushToolResults()
                    val blocks = buildJsonArray {
                        message.content?.takeIf { it.isNotBlank() }?.let {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", it)
                            })
                        }
                        message.tool_calls.orEmpty().forEach { call ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.function.name)
                                put("input", parseArgs(call.function.arguments))
                            })
                        }
                    }
                    if (blocks.isEmpty()) continue // Anthropic rejects empty content
                    messages += buildJsonObject {
                        put("role", "assistant")
                        put("content", blocks)
                    }
                    lastRole = "assistant"
                }
            }
        }
        flushToolResults()

        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", MAX_TOKENS)
            if (request.stream) put("stream", true)
            if (system.isNotEmpty()) put("system", system.toString())
            put("messages", JsonArray(messages.toList()))
            request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                put("tools", buildJsonArray {
                    tools.forEach { spec ->
                        add(buildJsonObject {
                            put("name", spec.function.name)
                            put("description", spec.function.description)
                            put("input_schema", spec.function.parameters)
                        })
                    }
                })
            }
        }
    }

    private fun mergeUserText(last: JsonObject, text: String): JsonObject {
        val content = last["content"]
        return buildJsonObject {
            put("role", "user")
            when (content) {
                is JsonPrimitive -> put("content", content.contentOrNull.orEmpty() + "\n\n" + text)
                is JsonArray -> put(
                    "content",
                    JsonArray(content + buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }),
                )
                else -> put("content", text)
            }
        }
    }

    private fun parseArgs(argumentsJson: String): JsonObject {
        val parsed = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
        return parsed as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Anthropic POST /messages response -> our ChatResponse. */
    fun parseResponse(text: String): ChatResponse {
        val root = Json.parseToJsonElement(text).jsonObject
        val blocks = (root["content"] as? JsonArray).orEmpty()
        val textParts = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (block in blocks) {
            val obj = block as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> textParts.append(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "tool_use" -> toolCalls += ToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "call_${toolCalls.size}",
                    function = FunctionCall(
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        arguments = (obj["input"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
                    ),
                )
            }
        }
        val stop = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        val usage = root["usage"] as? JsonObject
        return ChatResponse(
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        content = textParts.toString().ifBlank { null },
                        tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                    ),
                    finish_reason = when (stop) {
                        "tool_use" -> "tool_calls"
                        "end_turn", "stop_sequence", "max_tokens" -> "stop"
                        else -> stop
                    },
                )
            ),
            usage = ChatUsage(
                prompt_tokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull,
                completion_tokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull,
            ),
        )
    }

    /**
     * Accumulates Anthropic SSE stream events into the same ChatResponse
     * shape [parseResponse] returns, emitting text deltas along the way.
     */
    class StreamAccumulator(private val onDelta: (String) -> Unit) {

        private val text = StringBuilder()
        private val toolJson = mutableMapOf<Int, StringBuilder>()
        private val toolMeta = linkedMapOf<Int, Pair<String, String>>() // index -> (id, name)
        private var stopReason: String? = null
        private var inputTokens: Long? = null
        private var outputTokens: Long? = null

        /** Feed one `data:` payload from the SSE stream. */
        fun onPayload(payload: String) {
            val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" ->
                    inputTokens = (obj["message"] as? JsonObject)
                        ?.get("usage")?.jsonPrimitive?.longOrNull
                "content_block_start" -> {
                    val index = obj["index"]?.jsonPrimitive?.longOrNull?.toInt() ?: return
                    val block = obj["content_block"] as? JsonObject ?: return
                    if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        toolMeta[index] =
                            (block["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index") to
                                (block["name"]?.jsonPrimitive?.contentOrNull ?: "")
                        toolJson.getOrPut(index) { StringBuilder() }
                    }
                }
                "content_block_delta" -> {
                    val index = obj["index"]?.jsonPrimitive?.longOrNull?.toInt() ?: return
                    val delta = obj["delta"] as? JsonObject ?: return
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val piece = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (piece.isNotEmpty()) {
                                text.append(piece)
                                onDelta(piece)
                            }
                        }
                        "input_json_delta" ->
                            toolJson.getOrPut(index) { StringBuilder() }
                                .append(delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: "")
                    }
                }
                "message_delta" -> {
                    val delta = obj["delta"] as? JsonObject
                    stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull ?: stopReason
                    outputTokens = (obj["usage"] as? JsonObject)
                        ?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: outputTokens
                }
            }
        }

        fun build(): ChatResponse = ChatResponse(
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        content = text.toString().ifBlank { null },
                        tool_calls = toolMeta.entries.sortedBy { it.key }.map { (index, meta) ->
                            ToolCall(
                                id = meta.first,
                                function = FunctionCall(
                                    name = meta.second,
                                    arguments = toolJson[index]?.toString().orEmpty().ifBlank { "{}" },
                                ),
                            )
                        }.takeIf { it.isNotEmpty() },
                    ),
                    finish_reason = when (stopReason) {
                        "tool_use" -> "tool_calls"
                        "end_turn", "stop_sequence", "max_tokens" -> "stop"
                        else -> stopReason
                    },
                )
            ),
            usage = ChatUsage(
                prompt_tokens = inputTokens,
                completion_tokens = outputTokens,
            ),
        )
    }
}
