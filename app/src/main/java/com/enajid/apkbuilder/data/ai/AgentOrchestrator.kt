package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.CancellationException

/**
 * ChatApi wrapper that tries several providers in order and sticks with the
 * first one that answers. This is what makes free tiers usable: when a model
 * hits its rate limit, the next one takes over transparently.
 */
class FallbackChatApi(private val clients: List<ChatApi>) : ChatApi {

    private var preferred = 0

    init {
        require(clients.isNotEmpty()) { "at least one client required" }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        var last: Exception? = null
        for (offset in clients.indices) {
            val index = (preferred + offset) % clients.size
            try {
                val response = clients[index].chat(request)
                preferred = index
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no providers configured")
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

        val filesSection = access.writtenPaths.joinToString("\n\n") { path ->
            val content = access.readFile(path).orEmpty()
            "--- $path ---\n" + content.take(MAX_REVIEW_FILE_CHARS)
        }.take(MAX_REVIEW_TOTAL_CHARS)

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
