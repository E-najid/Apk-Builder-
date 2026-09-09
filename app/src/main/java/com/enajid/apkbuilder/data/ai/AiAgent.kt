package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the agent is allowed to do with the currently open project. */
interface AgentProjectAccess {
    suspend fun listPaths(): List<String>
    suspend fun readFile(path: String): String?
    suspend fun writeFile(path: String, content: String)
}

sealed interface AgentEvent {
    /** Interim text the model produced while working ("Let me check …"). */
    data class AssistantText(val text: String) : AgentEvent

    /** A tool was executed ("read MainActivity.kt"). */
    data class ToolActivity(val label: String) : AgentEvent
}

data class AgentResult(val finalText: String?, val steps: Int)

/**
 * The coding-agent loop: sends the conversation (with the project context) to
 * the chat model and executes the tools it asks for until it replies with
 * plain text (done) or the step limit is hit.
 *
 * File writes go through [AgentProjectAccess.writeFile] — the caller decides
 * what a "write" means (in APK Builder: an editable draft, never a direct
 * push, so the user always reviews before anything reaches GitHub).
 */
class AiAgent(private val api: ChatApi, private val project: AgentProjectAccess) {

    fun tools(): List<ToolSpec> = listOf(
        ToolSpec(
            function = FunctionSpec(
                name = "read_file",
                description = "Read a file from the current project. Returns its content.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Project-relative file path")
                        })
                    })
                    put("required", buildJsonArray { add("path") })
                },
            ),
        ),
        ToolSpec(
            function = FunctionSpec(
                name = "write_file",
                description = "Create or overwrite a file with new content. " +
                    "Changes are applied as editable drafts the user reviews.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                        put("content", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray {
                        add("path")
                        add("content")
                    })
                },
            ),
        ),
    )

    /**
     * @param history prior user/assistant text turns (kept across messages)
     * @return the agent's final answer, or null when the step limit was hit
     */
    suspend fun run(
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        userMessage: String,
        onEvent: (AgentEvent) -> Unit,
    ): AgentResult {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(role = "system", content = systemPrompt)
        messages += history
        messages += ChatMessage(role = "user", content = userMessage)

        var step = 0
        while (step < MAX_STEPS) {
            val response = api.chat(
                ChatRequest(model = model, messages = messages.toList(), tools = tools())
            )
            val choice = response.choices.firstOrNull()
                ?: return AgentResult(null, step)
            val assistant = choice.message
                ?: return AgentResult(null, step)
            val toolCalls = assistant.tool_calls.orEmpty()

            // Text alongside tool calls is interim narration; text without
            // tool calls is the final answer (no event, returned instead).
            if (toolCalls.isNotEmpty()) {
                assistant.content?.takeIf { it.isNotBlank() }?.let {
                    onEvent(AgentEvent.AssistantText(it))
                }
            } else {
                return AgentResult(
                    assistant.content?.takeIf { it.isNotBlank() } ?: "Done.",
                    step + 1,
                )
            }

            messages += ChatMessage(
                role = "assistant",
                content = assistant.content,
                tool_calls = assistant.tool_calls,
            )
            for (call in toolCalls) {
                onEvent(AgentEvent.ToolActivity(toolLabel(call)))
                val result = try {
                    executeTool(call.function.name, call.function.arguments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "error: ${e.message ?: e.javaClass.simpleName}"
                }
                messages += ChatMessage(role = "tool", content = result, tool_call_id = call.id)
            }
            step++
        }
        return AgentResult(null, step)
    }

    private fun toolLabel(call: ToolCall): String {
        val args = runCatching {
            Json.parseToJsonElement(call.function.arguments).jsonObject
        }.getOrNull()
        val path = args?.get("path")?.jsonPrimitive?.contentOrNull
        return when (call.function.name) {
            "read_file" -> "read ${path ?: "?"}"
            "write_file" -> "write ${path ?: "?"}"
            else -> call.function.name
        }
    }

    private suspend fun executeTool(name: String, argumentsJson: String): String {
        val args = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (e: Exception) {
            return "error: arguments must be a JSON object"
        }
        return when (name) {
            "read_file" -> {
                val path = args.stringArg("path") ?: return "error: missing 'path'"
                val content = project.readFile(path)
                    ?: return "error: file not found: $path (use the exact paths from the file list)"
                if (content.length > MAX_READ_CHARS) {
                    content.take(MAX_READ_CHARS) + "\n…(truncated)"
                } else {
                    content
                }
            }
            "write_file" -> {
                val path = args.stringArg("path") ?: return "error: missing 'path'"
                val content = args.stringArg("content") ?: return "error: missing 'content'"
                if (path.isBlank() || path.startsWith("/") || path.split('/').contains("..")) {
                    return "error: invalid path"
                }
                project.writeFile(path, content)
                "ok: wrote ${content.length} chars to $path"
            }
            else -> "error: unknown tool '$name'"
        }
    }

    private fun JsonObject.stringArg(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_STEPS = 10
        const val MAX_READ_CHARS = 6000
    }
}
