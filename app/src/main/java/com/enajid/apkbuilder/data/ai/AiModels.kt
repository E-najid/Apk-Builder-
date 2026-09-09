package com.enajid.apkbuilder.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Minimal OpenAI-compatible chat-completion DTOs. OmniRoute speaks this
 * format on its local endpoint (http://localhost:20128/v1), so these work
 * for any OpenAI-style server.
 */
@Serializable
data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    /** JSON-encoded arguments, exactly like the OpenAI wire format. */
    val arguments: String,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    val stream: Boolean = false,
)

@Serializable
data class AssistantMessage(
    val role: String = "assistant",
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: AssistantMessage? = null,
    val finish_reason: String? = null,
)

@Serializable
data class ChatUsage(
    val prompt_tokens: Long? = null,
    val completion_tokens: Long? = null,
    val total_tokens: Long? = null,
)

@Serializable
data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
data class ModelId(val id: String = "", val owned_by: String? = null)

@Serializable
data class ModelsResponse(val data: List<ModelId> = emptyList())
