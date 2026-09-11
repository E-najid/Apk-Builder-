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

/** App name + applicationId snapshot for the set_app_config tool. */
data class AppConfigSnapshot(val appName: String, val applicationId: String)

/**
 * What the agent is allowed to do with the currently open project.
 *
 * The first three members are required everywhere. The rest have default
 * "not supported" implementations so simple hosts (and JVM tests) keep
 * compiling — hosts override what they can provide.
 */
interface AgentProjectAccess {
    suspend fun listPaths(): List<String>
    suspend fun readFile(path: String): String?
    suspend fun writeFile(path: String, content: String)

    /**
     * Marks a file for deletion — as a draft the user still has to save.
     * Returns an error message, or null on success.
     */
    suspend fun deleteFile(path: String): String? =
        "error: file deletion is not supported for this project"

    /** Current app name + applicationId, when the host supports project config. */
    suspend fun readAppConfig(): AppConfigSnapshot? = null

    /**
     * Applies an app name / applicationId change — as drafts. Returns an
     * error message, or null on success.
     */
    suspend fun applyAppConfig(appName: String?, applicationId: String?): String? =
        "error: app config changes are not supported for this project"

    /** Latest CI build status text for the project repo, or null when unknown. */
    suspend fun buildStatus(): String? = null

    /** Recent commits, newest first, already formatted for display. */
    suspend fun gitLog(limit: Int): List<String> = emptyList()
}

sealed interface AgentEvent {
    /** Live text piece as it streams in (also used for the final answer). */
    data class TextDelta(val text: String) : AgentEvent

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
class AiAgent(
    private val api: ChatApi,
    private val project: AgentProjectAccess,
    private val maxSteps: Int = MAX_STEPS,
) {

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
        ToolSpec(
            function = FunctionSpec(
                name = "list_files",
                description = "List every file in the current project, one path per line. " +
                    "Use after creating or deleting files to see the fresh list.",
                parameters = buildJsonObject { put("type", "object") },
            ),
        ),
        ToolSpec(
            function = FunctionSpec(
                name = "search_files",
                description = "Search all project files with a regular expression. " +
                    "Returns matches as 'path:line: text'. Far cheaper than reading files one by one.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Regular expression to search for")
                        })
                        put("max_results", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of matches to return (default 40)")
                        })
                    })
                    put("required", buildJsonArray { add("pattern") })
                },
            ),
        ),
        ToolSpec(
            function = FunctionSpec(
                name = "delete_file",
                description = "Mark a file for deletion. Applied as a draft when the user saves. " +
                    "Never delete files the app needs to build (gradle files, the manifest, the workflow).",
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
                name = "set_app_config",
                description = "Change the app's display name and/or application ID (package). " +
                    "Applied as drafts the user reviews before saving.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("app_name", buildJsonObject {
                            put("type", "string")
                            put("description", "New app display name (optional)")
                        })
                        put("application_id", buildJsonObject {
                            put("type", "string")
                            put("description", "New application ID, e.g. com.example.app (optional)")
                        })
                    })
                },
            ),
        ),
        ToolSpec(
            function = FunctionSpec(
                name = "get_build_status",
                description = "Get the latest GitHub Actions build for this project: " +
                    "run number, status, conclusion and failing steps, if any.",
                parameters = buildJsonObject { put("type", "object") },
            ),
        ),
        ToolSpec(
            function = FunctionSpec(
                name = "git_log",
                description = "List recent commits of the project repository, newest first.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "How many commits to list (default 10, max 30)")
                        })
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
        while (step < maxSteps) {
            var streamedThisCall = false
            val response = api.chatStream(
                ChatRequest(model = model, messages = messages.toList(), tools = tools())
            ) { delta ->
                streamedThisCall = true
                onEvent(AgentEvent.TextDelta(delta))
            }
            val choice = response.choices.firstOrNull()
            if (choice == null) {
                AiDebugLog.error("agent", "provider উত্তরে কোনো choice পাঠায়নি (step ${step + 1})")
                throw AiException("provider-এর উত্তরে কোনো choice ছিল না — বিস্তারিত 🐞 debug এ।")
            }
            val assistant = choice.message
            if (assistant == null) {
                AiDebugLog.error("agent", "provider উত্তরে message খালি (step ${step + 1})")
                throw AiException("provider-এর উত্তরে message ছিল না — বিস্তারিত 🐞 debug এ।")
            }
            val toolCalls = assistant.tool_calls.orEmpty()

            // Text alongside tool calls is interim narration; text without
            // tool calls is the final answer (no event, returned instead).
            if (toolCalls.isNotEmpty()) {
                AiDebugLog.info(
                    "agent",
                    "step ${step + 1}/$maxSteps: ${toolCalls.size} tool call(s)" +
                        (assistant.content?.takeIf { it.isNotBlank() }?.let { ", note ${it.length} ch" } ?: ""),
                )
                assistant.content?.takeIf { it.isNotBlank() }?.let {
                    if (!streamedThisCall) onEvent(AgentEvent.TextDelta(it))
                }
            } else {
                val final = assistant.content?.takeIf { it.isNotBlank() }
                if (final == null) {
                    // Free models sometimes answer tool prompts with an empty
                    // body — fail loudly instead of pretending "Done.".
                    AiDebugLog.warn(
                        "agent",
                        "model খালি উত্তর দিয়েছে (finish_reason=${choice.finish_reason ?: "null"}) — " +
                            "সাধারণত মানে এই model-এ tool-calling ঠিকমতো কাজ করে না",
                    )
                    throw AiException(
                        "মডেল খালি উত্তর দিলো — এই model-এ সাধারণত tool/function calling কাজ করে না। " +
                            "⚙ setup-এ গিয়ে অন্য একটা model দিয়ে দেখো। বিস্তারিত 🐞 debug এ।"
                    )
                }
                if (!streamedThisCall) onEvent(AgentEvent.TextDelta(final))
                return AgentResult(final, step + 1)
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
                AiDebugLog.info(
                    "agent",
                    "step ${step + 1}/$MAX_STEPS: ${call.function.name} → ${result.take(120)}",
                )
            }
            step++
        }
        AiDebugLog.warn("agent", "ধাপ সীমা ($maxSteps) শেষ — কাজ অসম্পূর্ণ থেমে গেল")
        return AgentResult(null, step)
    }

    private fun toolLabel(call: ToolCall): String {
        val args = runCatching {
            Json.parseToJsonElement(call.function.arguments).jsonObject
        }.getOrNull()
        val path = args?.get("path")?.jsonPrimitive?.contentOrNull
        val pattern = args?.get("pattern")?.jsonPrimitive?.contentOrNull
        return when (call.function.name) {
            "read_file" -> "read ${path ?: "?"}"
            "write_file" -> "write ${path ?: "?"}"
            "list_files" -> "list files"
            "search_files" -> "search /${pattern ?: "?"}/"
            "delete_file" -> "delete ${path ?: "?"}"
            "set_app_config" -> "app config"
            "get_build_status" -> "check build"
            "git_log" -> "git log"
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
            "list_files" -> {
                val paths = project.listPaths()
                if (paths.isEmpty()) {
                    "no files yet"
                } else {
                    paths.take(MAX_LIST_FILES).joinToString("\n") +
                        if (paths.size > MAX_LIST_FILES) "\n…(${paths.size - MAX_LIST_FILES} more)" else ""
                }
            }
            "search_files" -> {
                val pattern = args.stringArg("pattern") ?: return "error: missing 'pattern'"
                val maxResults = args.intArg("max_results")?.coerceIn(1, 100) ?: MAX_SEARCH_RESULTS
                val regex = try {
                    Regex(pattern)
                } catch (e: Exception) {
                    return "error: invalid regex: ${e.message}"
                }
                val matches = StringBuilder()
                var count = 0
                for (path in project.listPaths()) {
                    if (count >= maxResults || matches.length > MAX_SEARCH_OUTPUT) break
                    val content = project.readFile(path) ?: continue
                    val lines = content.lines()
                    for (index in lines.indices) {
                        if (count >= maxResults || matches.length > MAX_SEARCH_OUTPUT) break
                        val line = lines[index]
                        if (regex.containsMatchIn(line)) {
                            matches.appendLine("$path:${index + 1}: ${line.trim().take(160)}")
                            count++
                        }
                    }
                }
                when {
                    count == 0 -> "no matches for /$pattern/"
                    count >= maxResults || matches.length > MAX_SEARCH_OUTPUT ->
                        matches.toString() + "…(result list truncated)"
                    else -> matches.toString()
                }
            }
            "delete_file" -> {
                val path = args.stringArg("path") ?: return "error: missing 'path'"
                project.deleteFile(path) ?: "ok: $path marked for deletion (applied when the user saves)"
            }
            "set_app_config" -> {
                val appName = args.stringArg("app_name")?.trim()
                val appId = args.stringArg("application_id")?.trim()
                if (appName.isNullOrBlank() && appId.isNullOrBlank()) {
                    return "error: give app_name or application_id"
                }
                project.applyAppConfig(
                    appName?.takeIf { it.isNotBlank() },
                    appId?.takeIf { it.isNotBlank() },
                ) ?: "ok: config changes saved as drafts (applied when the user saves)"
            }
            "get_build_status" ->
                project.buildStatus() ?: "error: no build information available for this project"
            "git_log" -> {
                val limit = args.intArg("limit")?.coerceIn(1, 30) ?: 10
                val commits = project.gitLog(limit)
                if (commits.isEmpty()) "no commits found" else commits.joinToString("\n")
            }
            else -> "error: unknown tool '$name'"
        }
    }

    private fun JsonObject.stringArg(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    /** Lenient int arg: accepts both 40 and "40". */
    private fun JsonObject.intArg(name: String): Int? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    companion object {
        const val MAX_STEPS = 25
        const val MAX_READ_CHARS = 6000
        const val MAX_LIST_FILES = 500
        const val MAX_SEARCH_RESULTS = 40
        const val MAX_SEARCH_OUTPUT = 6000
    }
}
