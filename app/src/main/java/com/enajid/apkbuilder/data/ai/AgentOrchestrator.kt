package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.CancellationException

/**
 * ChatApi wrapper that tries several providers in order and sticks with the
 * first one that answers. This is what makes free tiers usable: when a model
 * hits its rate limit, the next one takes over transparently. Every attempt
 * (success or failure) is recorded in [AiDebugLog].
 */
class FallbackChatApi(
    private val clients: List<ChatApi>,
    private val labels: List<String> = emptyList(),
    /** Notified on every failed attempt, before the next model is tried. */
    private val onSwitch: (reason: String) -> Unit = {},
) : ChatApi {

    private var preferred = 0

    init {
        require(clients.isNotEmpty()) { "at least one client required" }
    }

    private fun label(index: Int) = labels.getOrNull(index) ?: "provider ${index + 1}"

    override suspend fun chat(request: ChatRequest): ChatResponse =
        chatStream(request) {}

    override suspend fun chatStream(
        request: ChatRequest,
        onDelta: (String) -> Unit,
    ): ChatResponse {
        val failures = mutableListOf<String>()
        for (offset in clients.indices) {
            val index = (preferred + offset) % clients.size
            try {
                val response = clients[index].chatStream(request, onDelta)
                if (offset > 0) {
                    AiDebugLog.warn("fallback", "${label(index)} fallback হিসেবে কাজ করছে ✓")
                }
                preferred = index
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${label(index)}: ${e.message}"
                AiDebugLog.warn(
                    "fallback",
                    "${label(index)} ব্যর্থ — পরের model-এ যাওয়া হচ্ছে…",
                    details = e.message,
                )
                onSwitch("${label(index)}: ${e.message ?: "ব্যর্থ"}")
            }
        }
        val summary = failures.joinToString("\n")
        AiDebugLog.error("fallback", "সব model ব্যর্থ (${clients.size}টা চেষ্টা হয়েছে)")
        throw AiException("সব model ব্যর্থ:\n$summary")
    }
}

/**
 * Wraps the project access and remembers which files the agent wrote during
 * a run — that's what the reviewer model then looks at.
 */
class RecordingAccess(private val inner: AgentProjectAccess) : AgentProjectAccess {

    val writtenPaths = LinkedHashSet<String>()

    override suspend fun listPaths(): List<String> = inner.listPaths()

    override suspend fun readFile(path: String): String? = inner.readFile(path)

    override suspend fun writeFile(path: String, content: String) {
        inner.writeFile(path, content)
        writtenPaths += path
    }

    override suspend fun deleteFile(path: String): String? = inner.deleteFile(path)

    override suspend fun readAppConfig(): AppConfigSnapshot? = inner.readAppConfig()

    override suspend fun applyAppConfig(appName: String?, applicationId: String?): String? =
        inner.applyAppConfig(appName, applicationId)

    override suspend fun buildStatus(): String? = inner.buildStatus()

    override suspend fun gitLog(limit: Int): List<String> = inner.gitLog(limit)
}

/**
 * Multi-model agent pipeline:
 *
 *  1. the **coder** model runs the normal tool loop (read/write files),
 *  2. if it changed anything, the **reviewer** model inspects the changed
 *     files once and either approves (replies "OK") or lists issues,
 *  3. issues are sent back to the coder for one fix round.
 *
 * The coder's ChatApi is usually a [FallbackChatApi], so provider failures
 * are absorbed automatically.
 */
class MultiModelAgent(
    private val chat: ChatApi,
    private val reviewer: ChatApi? = null,
    private val reviewerModel: String? = null,
) {

    suspend fun run(
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        userMessage: String,
        project: AgentProjectAccess,
        onEvent: (AgentEvent) -> Unit,
    ): AgentResult {
        val access = RecordingAccess(project)
        val agent = AiAgent(chat, access)
        var result = agent.run(model, systemPrompt, history, userMessage, onEvent)

        val reviewText = runReview(userMessage, result, access)
        if (reviewText != null) {
            onEvent(AgentEvent.AssistantText("🔍 Reviewer: $reviewText"))
            result = agent.run(
                model = model,
                systemPrompt = systemPrompt,
                history = history +
                    ChatMessage(role = "user", content = userMessage) +
                    ChatMessage(role = "assistant", content = result.finalText ?: ""),
                userMessage = "Code review feedback on your changes:\n$reviewText\n\n" +
                    "Read the affected files and fix these issues now.",
                onEvent = onEvent,
            )
        }
        return result
    }

    /**
     * One-shot review of the changed files. Returns the issue list to fix, or
     * null when everything is fine / no reviewer / nothing was written / the
     * reviewer itself failed (never fails the task).
     */
    private suspend fun runReview(
        userMessage: String,
        result: AgentResult,
        access: RecordingAccess,
    ): String? {
        val reviewerApi = reviewer ?: return null
        val reviewerId = reviewerModel ?: return null
        val finalText = result.finalText ?: return null
        if (access.writtenPaths.isEmpty()) return null

        val filesBuilder = StringBuilder()
        for (path in access.writtenPaths) {
            if (filesBuilder.length >= MAX_REVIEW_TOTAL_CHARS) break
            val content = access.readFile(path).orEmpty()
            if (filesBuilder.isNotEmpty()) filesBuilder.append("\n\n")
            filesBuilder.append("--- ").append(path).append(" ---\n")
                .append(content.take(MAX_REVIEW_FILE_CHARS))
        }
        val filesSection = filesBuilder.toString().take(MAX_REVIEW_TOTAL_CHARS)

        val request = ChatRequest(
            model = reviewerId,
            messages = listOf(
                ChatMessage(role = "system", content = REVIEWER_SYSTEM),
                ChatMessage(
                    role = "user",
                    content = "The user's request:\n$userMessage\n\n" +
                        "The agent's summary:\n$finalText\n\n" +
                        "Changed files:\n\n$filesSection",
                ),
            ),
        )
        return try {
            val response = reviewerApi.chat(request)
            val text = response.choices.firstOrNull()?.message?.content?.trim()
            when {
                text.isNullOrBlank() -> null
                text.equals("OK", ignoreCase = true) ||
                    text.equals("LGTM", ignoreCase = true) ||
                    text.startsWith("OK.", ignoreCase = true) ||
                    text.startsWith("OK\n", ignoreCase = true) -> null
                else -> text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a broken reviewer must not lose the coder's work
        }
    }

    companion object {
        private const val MAX_REVIEW_FILE_CHARS = 3_000
        private const val MAX_REVIEW_TOTAL_CHARS = 9_000

        private const val REVIEWER_SYSTEM =
            "You are a strict senior Android/Kotlin code reviewer. You receive the " +
                "changed files of a small Jetpack Compose Android project. Check for " +
                "concrete problems only: compile errors, missing imports, wrong API " +
                "usage, references to symbols that don't exist in the project, and " +
                "clear logic bugs. Ignore style nits. Reply with a short numbered " +
                "list of issues (file + problem + one-line fix), or reply with " +
                "exactly 'OK' if the code is good. Maximum 6 issues. Be terse."
    }
}
