package com.enajid.apkbuilder.ui.editor

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.GitRepository
import com.enajid.apkbuilder.data.LocalProjectStore
import com.enajid.apkbuilder.data.ProjectsRepository
import com.enajid.apkbuilder.data.TemplateRenderer
import com.enajid.apkbuilder.data.ai.AgentEvent
import com.enajid.apkbuilder.data.ai.AiConfig
import com.enajid.apkbuilder.data.ai.AgentProjectAccess
import com.enajid.apkbuilder.data.ai.AiAgent
import com.enajid.apkbuilder.data.ai.AiSettingsStore
import com.enajid.apkbuilder.data.ai.ChatMessage
import com.enajid.apkbuilder.data.ai.OmniRouteClient
import com.enajid.apkbuilder.data.ai.OmniRouteStatus
import com.enajid.apkbuilder.data.friendlyMessage
import com.enajid.apkbuilder.domain.ProjectFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    /**
     * [revision] bumps whenever the AI agent rewrites the currently open file,
     * so the editor's text state re-initializes with the new content.
     */
    data class LoadedFile(
        val path: String,
        val content: String,
        val binary: Boolean,
        val revision: Int = 0,
    )

    data class EditorUiState(
        val loading: Boolean = true,
        val error: String? = null,
        val repoName: String = "",
        val branch: String = "main",
        val paths: List<String> = emptyList(),
        val selectedPath: String? = null,
        val selectedIsBinary: Boolean = false,
        val fileLoading: Boolean = false,
        val dirty: Set<String> = emptySet(),
        val saving: Boolean = false,
        val deleting: Boolean = false,
        val message: String? = null,
    )

    // ------------------------------------------------------------- agent ui --

    data class AgentBubble(
        val id: Long,
        val fromUser: Boolean,
        val text: String,
        val kind: Kind = Kind.TEXT,
    ) {
        enum class Kind { TEXT, TOOL, ERROR }
    }

    data class AgentUiState(
        val open: Boolean = false,
        val hasKey: Boolean = false,
        val baseUrl: String = "",
        val model: String = "",
        val checking: Boolean = false,
        /** null = unknown, true = OmniRoute reachable, false = down. */
        val reachable: Boolean? = null,
        val messages: List<AgentBubble> = emptyList(),
        val busy: Boolean = false,
        val models: List<String> = emptyList(),
        val pendingInput: String? = null,
        val notice: String? = null,
    )

    private val container = (application as ApkBuilderApp).container
    private val git: GitRepository = container.gitRepository
    private val projects: ProjectsRepository = container.projectsRepository
    private val localStore: LocalProjectStore = container.localProjectStore
    private val aiSettings: AiSettingsStore = container.aiSettings
    private val omniRoute: OmniRouteClient = container.omniRouteClient

    private val owner: String = savedStateHandle.get<String>("owner") ?: ""
    private val repo: String = savedStateHandle.get<String>("repo") ?: ""

    private val _state = MutableStateFlow(EditorUiState())
    val state = _state.asStateFlow()

    private val _selectedFile = MutableStateFlow<LoadedFile?>(null)
    val selectedFile = _selectedFile.asStateFlow()

    private val _agent = MutableStateFlow(AgentUiState())
    val agentState = _agent.asStateFlow()

    private var serverPaths: List<String> = emptyList()
    /** Current blob sha per path — needed to delete files via the Contents API. */
    private var shasByPath: Map<String, String> = emptyMap()
    private val dirtyContents = LinkedHashMap<String, String>()
    private var branch: String = "main"
    private var debounce: Job? = null

    private var agentJob: Job? = null
    private var bubbleId = 0L
    private val agentHistory = mutableListOf<ChatMessage>()
    private var gradleInfo: String? = null

    init {
        load()
    }

    // ------------------------------------------------------------ project --

    fun load() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, error = null) }
                val repoInfo = projects.getRepo(owner, repo)
                branch = repoInfo.default_branch.ifBlank { "main" }
                val entries = git.listFiles(owner, repo, branch)
                serverPaths = entries.map { it.path }
                shasByPath = entries.associate { it.path to it.sha }
                val localDirty = withContext(Dispatchers.IO) { localStore.loadDirty(owner, repo) }
                dirtyContents.clear()
                dirtyContents.putAll(localDirty)
                _state.update {
                    it.copy(
                        loading = false,
                        repoName = repoInfo.name,
                        branch = branch,
                        paths = (serverPaths + dirtyContents.keys).distinct().sorted(),
                        dirty = dirtyContents.keys.toSet(),
                    )
                }
                val initial = serverPaths.firstOrNull { it.endsWith("MainActivity.kt") }
                    ?: serverPaths.firstOrNull { it.endsWith(".kt") }
                    ?: serverPaths.firstOrNull()
                if (initial != null) select(initial)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.friendlyMessage()) }
            }
        }
    }

    fun select(path: String) {
        if (path == _state.value.selectedPath) return
        viewModelScope.launch {
            try {
                _state.update {
                    it.copy(selectedPath = path, fileLoading = true, selectedIsBinary = false)
                }
                val cached = dirtyContents[path]
                val content: String
                var binary = false
                if (cached != null) {
                    content = cached
                } else {
                    val bytes = git.readFile(owner, repo, branch, path)
                    if (bytes.contains(0.toByte())) {
                        binary = true
                        content = ""
                    } else {
                        content = String(bytes, Charsets.UTF_8)
                    }
                }
                _state.update { it.copy(fileLoading = false, selectedIsBinary = binary) }
                _selectedFile.value = LoadedFile(path, content, binary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(fileLoading = false, message = "Couldn’t open the file: ${e.friendlyMessage()}")
                }
            }
        }
    }

    fun onContentChange(text: String) {
        val path = _state.value.selectedPath ?: return
        if (_state.value.selectedIsBinary) return
        dirtyContents[path] = text
        _state.update { it.copy(dirty = dirtyContents.keys.toSet()) }
        debounce?.cancel()
        debounce = viewModelScope.launch {
            delay(600)
            withContext(Dispatchers.IO) { localStore.markDirty(owner, repo, path, text) }
        }
    }

    fun save() {
        viewModelScope.launch {
            if (commitPending()) {
                _state.update { it.copy(message = "All changes saved to GitHub") }
            }
        }
    }

    /**
     * Commits all unsaved changes in a single commit. Returns true when the
     * project is safely on GitHub (also true when there was nothing to save).
     */
    suspend fun commitPending(): Boolean {
        if (dirtyContents.isEmpty()) return true
        return try {
            _state.update { it.copy(saving = true) }
            val files = dirtyContents.map { (path, content) ->
                TemplateRenderer.RenderedFile(path, content.toByteArray(Charsets.UTF_8))
            }
            git.pushFiles(
                owner = owner,
                repo = repo,
                branch = branch,
                files = files,
                deletions = emptyList(),
                message = "Save changes from APK Builder",
            )
            dirtyContents.clear()
            withContext(Dispatchers.IO) { localStore.clearDirty(owner, repo) }
            _state.update { it.copy(saving = false, dirty = emptySet()) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(saving = false, message = "Couldn’t save: ${e.friendlyMessage()}") }
            false
        }
    }

    /**
     * Deletes a file from GitHub (or, if it was never committed, just from
     * the local draft store). If the deleted file is open, switches to
     * another one — or the empty state when none remain.
     */
    fun deleteFile(path: String) {
        ProjectFiles.criticalReason(path)?.let { reason ->
            _state.update { it.copy(message = reason) }
            return
        }
        if (_state.value.deleting) return
        viewModelScope.launch {
            try {
                _state.update { it.copy(deleting = true) }
                val sha = shasByPath[path]
                if (sha != null) {
                    git.deleteFile(owner, repo, branch, path, sha)
                    serverPaths = serverPaths - path
                    shasByPath = shasByPath - path
                }
                dirtyContents.remove(path)
                withContext(Dispatchers.IO) { localStore.removeDirtyFile(owner, repo, path) }

                val remaining = (serverPaths + dirtyContents.keys).distinct().sorted()
                val wasSelected = _state.value.selectedPath == path
                if (wasSelected) {
                    val next = remaining.firstOrNull { it.endsWith(".kt") } ?: remaining.firstOrNull()
                    if (next != null) {
                        _state.update {
                            it.copy(
                                deleting = false,
                                paths = remaining,
                                dirty = dirtyContents.keys.toSet(),
                                selectedPath = null,
                                message = "Deleted $path",
                            )
                        }
                        _selectedFile.value = null
                        select(next)
                    } else {
                        _selectedFile.value = null
                        _state.update {
                            it.copy(
                                deleting = false,
                                paths = remaining,
                                dirty = dirtyContents.keys.toSet(),
                                selectedPath = null,
                                fileLoading = false,
                                selectedIsBinary = false,
                                message = "Deleted $path",
                            )
                        }
                    }
                } else {
                    _state.update {
                        it.copy(
                            deleting = false,
                            paths = remaining,
                            dirty = dirtyContents.keys.toSet(),
                            message = "Deleted $path",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(deleting = false, message = "Couldn’t delete $path: ${e.friendlyMessage()}")
                }
            }
        }
    }

    fun newFile(pathRaw: String) {
        val path = pathRaw.trim().removePrefix("/").replace('\\', '/')
        val segments = path.split('/')
        val error = when {
            path.isBlank() -> "Enter a file path"
            segments.any { it.isEmpty() || it == "." || it == ".." } ->
                "That path doesn't look right"
            !path.contains('.') -> "Add a file extension, for example .kt"
            !Regex("^[A-Za-z0-9._/-]+$").matches(path) ->
                "Use letters, digits, dots, dashes and slashes only"
            path in _state.value.paths -> "That file already exists"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(message = error) }
            return
        }
        val starter = starterContent(path)
        dirtyContents[path] = starter
        viewModelScope.launch {
            withContext(Dispatchers.IO) { localStore.markDirty(owner, repo, path, starter) }
        }
        _state.update {
            it.copy(
                paths = (serverPaths + dirtyContents.keys).distinct().sorted(),
                dirty = dirtyContents.keys.toSet(),
            )
        }
        select(path)
    }

    private fun starterContent(path: String): String {
        if (!path.endsWith(".kt")) return ""
        val javaIndex = path.indexOf("/java/")
        if (javaIndex < 0) return ""
        val pkg = path.substring(javaIndex + 6).substringBeforeLast('/').replace('/', '.')
        return if (pkg.isBlank()) "" else "package $pkg\n\n"
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------- ai agent --

    /** What the agent may do with this project (drafts only, never pushes). */
    private val projectAccess = object : AgentProjectAccess {

        override suspend fun listPaths(): List<String> =
            (serverPaths + dirtyContents.keys).distinct().sorted()

        override suspend fun readFile(path: String): String? {
            dirtyContents[path]?.let { return it }
            return try {
                val bytes = git.readFile(owner, repo, branch, path)
                if (bytes.contains(0.toByte())) "(binary file)" else String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        override suspend fun writeFile(path: String, content: String) {
            dirtyContents[path] = content
            withContext(Dispatchers.IO) { localStore.markDirty(owner, repo, path, content) }
            if (_state.value.selectedPath == path) {
                val revision = (_selectedFile.value?.revision ?: 0) + 1
                _selectedFile.value = LoadedFile(path, content, false, revision)
            }
            _state.update {
                it.copy(
                    paths = (serverPaths + dirtyContents.keys).distinct().sorted(),
                    dirty = dirtyContents.keys.toSet(),
                )
            }
        }
    }

    fun openAgent() {
        viewModelScope.launch {
            val config = aiSettings.current()
            _agent.update {
                it.copy(
                    open = true,
                    hasKey = config.hasKey,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    checking = true,
                )
            }
            refreshAgentStatus()
        }
    }

    fun closeAgent() = _agent.update { it.copy(open = false) }

    fun refreshAgentStatus() {
        viewModelScope.launch {
            try {
                _agent.update { it.copy(checking = true) }
                val reachable = omniRoute.ping() == OmniRouteStatus.RUNNING
                _agent.update { it.copy(checking = false, reachable = reachable) }
            } catch (e: Exception) {
                _agent.update { it.copy(checking = false, reachable = false) }
            }
        }
    }

    /** Saves the OmniRoute connection settings, then pings + tests the key. */
    fun saveAgentConfig(apiKey: String, baseUrl: String, model: String) {
        viewModelScope.launch {
            try {
                aiSettings.save(apiKey, baseUrl, model)
                val hasKey = apiKey.isNotBlank()
                val reachable = omniRoute.ping() == OmniRouteStatus.RUNNING
                var notice = "সেভ হয়েছে"
                if (hasKey && reachable) {
                    val models = runCatching { omniRoute.listModels() }.getOrDefault(emptyList())
                    if (models.isNotEmpty()) {
                        notice = "API key কাজ করছে ✓ (${models.size} models)"
                    }
                } else if (hasKey) {
                    notice = "সেভ হয়েছে, কিন্তু OmniRoute চালু নেই — Termux-এ omniroute চালাও"
                }
                _agent.update {
                    it.copy(
                        hasKey = hasKey,
                        baseUrl = baseUrl,
                        model = model,
                        reachable = reachable,
                        notice = notice,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agent.update { it.copy(notice = e.friendlyMessage()) }
            }
        }
    }

    fun loadAgentModels() {
        viewModelScope.launch {
            try {
                val models = omniRoute.listModels()
                _agent.update {
                    it.copy(
                        models = models,
                        notice = if (models.isEmpty()) "কোনো model পাওয়া যায়নি" else "${models.size} models লোড হয়েছে",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agent.update { it.copy(notice = e.friendlyMessage()) }
            }
        }
    }

    fun setAgentModel(model: String) {
        viewModelScope.launch {
            try {
                val config = aiSettings.current()
                aiSettings.save(config.apiKey, config.baseUrl, model)
                _agent.update { it.copy(model = model, notice = "Model সেভ হয়েছে: $model") }
            } catch (e: Exception) {
                _agent.update { it.copy(notice = e.friendlyMessage()) }
            }
        }
    }

    /**
     * Starts OmniRoute in Termux via the RUN_COMMAND intent when the user has
     * granted it; otherwise just opens Termux with instructions.
     */
    fun runOmniRoute(hasPermission: Boolean) {
        val context: Context = getApplication()
        if (hasPermission) {
            try {
                val intent = Intent("com.termux.RUN_COMMAND").apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/omniroute")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf<String>())
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                }
                ContextCompat.startForegroundService(context, intent)
                _agent.update { it.copy(notice = "OmniRoute চালু করা হলো… কয়েক সেকেন্ড পর ↻ চাপো") }
                viewModelScope.launch {
                    delay(5000)
                    refreshAgentStatus()
                }
                return
            } catch (e: Exception) {
                // fall through to the manual fallback below
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _agent.update { it.copy(notice = "Termux খুলে omniroute লিখে Enter দাও, তারপর ফিরে এসো") }
        } else {
            _agent.update { it.copy(notice = "Termux install করো আগে (সেটআপের ধাপ ১)") }
        }
    }

    /**
     * Opens the agent with a build-failure prompt (from the "Fix with AI"
     * button). When a key is already configured, the fix starts right away;
     * otherwise the sheet opens on the setup screen with the prompt queued.
     */
    fun seedAgentInput(prompt: String) {
        viewModelScope.launch {
            val config = aiSettings.current()
            _agent.update {
                it.copy(
                    open = true,
                    hasKey = config.hasKey,
                    baseUrl = config.baseUrl,
                    model = config.model,
                )
            }
            if (config.hasKey) {
                sendAgentMessage(prompt, _state.value.selectedPath, null)
            } else {
                _agent.update { it.copy(pendingInput = prompt) }
            }
        }
    }

    fun consumePendingInput() = _agent.update { it.copy(pendingInput = null) }

    fun consumeAgentNotice() = _agent.update { it.copy(notice = null) }

    fun stopAgent() {
        agentJob?.cancel()
    }

    fun sendAgentMessage(message: String, selectedPath: String?, selectedCode: String?) {
        val text = message.trim()
        if (text.isEmpty() || _agent.value.busy) return
        if (!_agent.value.hasKey) {
            _agent.update { it.copy(notice = "আগে API key সেট করো (⚙ সেটিংস)") }
            return
        }
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            _agent.update {
                it.copy(busy = true, messages = it.messages + bubble(fromUser = true, text = text))
            }
            try {
                val config = aiSettings.current()
                val system = buildSystemPrompt(selectedPath, selectedCode)
                val agent = AiAgent(omniRoute, projectAccess)
                val result = agent.run(
                    model = config.model.ifBlank { AiConfig.DEFAULT_MODEL },
                    systemPrompt = system,
                    history = agentHistory.toList(),
                    userMessage = text,
                ) { event ->
                    when (event) {
                        is AgentEvent.AssistantText -> _agent.update { s ->
                            s.copy(messages = s.messages + bubble(false, event.text))
                        }
                        is AgentEvent.ToolActivity -> _agent.update { s ->
                            s.copy(messages = s.messages + bubble(false, event.label, AgentBubble.Kind.TOOL))
                        }
                    }
                }
                val finalText = result.finalText
                    ?: "ধাপ সীমা শেষ — এখন পর্যন্ত যা হয়েছে দেখে আবার বলো।"
                if (result.finalText != null) {
                    agentHistory += ChatMessage(role = "user", content = text)
                    agentHistory += ChatMessage(role = "assistant", content = finalText)
                }
                _agent.update {
                    it.copy(
                        busy = false,
                        messages = it.messages +
                            bubble(false, finalText) +
                            bubble(false, "বদলগুলো draft হিসেবে এডিটরে বসেছে — Save → Build চেপে দেখো", AgentBubble.Kind.TOOL),
                    )
                }
            } catch (e: CancellationException) {
                _agent.update {
                    it.copy(
                        busy = false,
                        messages = it.messages + bubble(false, "থামানো হলো", AgentBubble.Kind.TOOL),
                    )
                }
            } catch (e: Exception) {
                _agent.update {
                    it.copy(
                        busy = false,
                        messages = it.messages + bubble(false, e.friendlyMessage(), AgentBubble.Kind.ERROR),
                    )
                }
            }
        }
    }

    private suspend fun buildSystemPrompt(selectedPath: String?, selectedCode: String?): String {
        val paths = projectAccess.listPaths()
        val gradle = gradleInfo ?: runCatching {
            val file = projectAccess.readFile("app/build.gradle.kts")
                ?: projectAccess.readFile("app/build.gradle")
            file?.let {
                val ns = Regex("""(?:namespace|applicationId)\s*[=:]\s*["']([A-Za-z0-9_.]+)""")
                    .find(it)?.groupValues?.get(1)
                val min = Regex("""minSdk\s*[=:]?\s*["']?(\d+)""").find(it)?.groupValues?.get(1)
                val target = Regex("""targetSdk\s*[=:]?\s*["']?(\d+)""").find(it)?.groupValues?.get(1)
                buildString {
                    if (ns != null) append("package: $ns")
                    if (min != null) append(", minSdk $min")
                    if (target != null) append(", targetSdk $target")
                }.takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.also { gradleInfo = it }

        return buildString {
            appendLine("You are an expert Android coding agent working inside APK Builder, an on-device IDE.")
            append("The project")
            if (!gradle.isNullOrBlank()) append(" ($gradle)")
            appendLine(" contains these files:")
            paths.take(200).forEach { appendLine(it) }
            if (paths.size > 200) appendLine("…(${paths.size - 200} more files)")
            if (selectedPath != null) {
                appendLine()
                append("The user currently has \"$selectedPath\" open")
                if (!selectedCode.isNullOrBlank()) {
                    appendLine(" and selected this code:")
                    appendLine("```")
                    appendLine(selectedCode.take(2000))
                    appendLine("```")
                } else {
                    appendLine(".")
                }
            }
            appendLine()
            appendLine("Rules:")
            appendLine("- read_file before editing a file you haven't seen in this conversation.")
            appendLine("- write_file creates or overwrites a file; make the smallest change that fulfills the request.")
            appendLine("- Only plain-text files. New Kotlin files go under app/src/main/java/ with a matching package declaration.")
            appendLine("- Available libraries: androidx.core, androidx.activity.compose, Compose UI + Material3 (compose BOM). No third-party libraries; standard Android/Compose APIs only.")
            appendLine("- When done, stop calling tools and reply with a short summary of what you changed.")
            appendLine("- Reply in the language the user writes in (often Bengali).")
        }
    }

    private fun bubble(fromUser: Boolean, text: String, kind: AgentBubble.Kind = AgentBubble.Kind.TEXT) =
        AgentBubble(id = ++bubbleId, fromUser = fromUser, text = text, kind = kind)
}
