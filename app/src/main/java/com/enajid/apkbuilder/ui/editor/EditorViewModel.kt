package com.enajid.apkbuilder.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.GitRepository
import com.enajid.apkbuilder.data.LocalProjectStore
import com.enajid.apkbuilder.data.ProjectsRepository
import com.enajid.apkbuilder.data.TemplateRenderer
import com.enajid.apkbuilder.data.ai.AgentEvent
import com.enajid.apkbuilder.data.ai.AgentProjectAccess
import com.enajid.apkbuilder.data.ai.AiDebugLog
import com.enajid.apkbuilder.data.ai.AiProfilesStore
import com.enajid.apkbuilder.data.ai.ChatMessage
import com.enajid.apkbuilder.data.ai.ChatRequest
import com.enajid.apkbuilder.data.ai.FallbackChatApi
import com.enajid.apkbuilder.data.ai.ModelProfile
import com.enajid.apkbuilder.data.ai.ModelRole
import com.enajid.apkbuilder.data.ai.MultiModelAgent
import com.enajid.apkbuilder.data.ai.ProviderChatClient
import com.enajid.apkbuilder.data.ai.Skill
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
        val streaming: Boolean = false,
    ) {
        enum class Kind { TEXT, TOOL, ERROR }
    }

    /** Result of the last "save & test" call for a profile (in-memory only). */
    data class ProfileTest(val ok: Boolean = false, val message: String = "")

    data class AgentUiState(
        val open: Boolean = false,
        val profiles: List<ModelProfile> = emptyList(),
        val skills: List<Skill> = emptyList(),
        val messages: List<AgentBubble> = emptyList(),
        val busy: Boolean = false,
        /** Model ids loaded for the add/edit dialog. */
        val models: List<String> = emptyList(),
        val modelsLoading: Boolean = false,
        val testingProfileId: Long? = null,
        val profileTests: Map<Long, ProfileTest> = emptyMap(),
        val pendingInput: String? = null,
        val notice: String? = null,
        /** Live AI debug log for the 🐞 pane. */
        val debugEntries: List<AiDebugLog.Entry> = emptyList(),
        val testRunning: Boolean = false,
        /** Bubble currently receiving streamed text, if any. */
        val streamingBubbleId: Long? = null,
    ) {
        val hasCoder: Boolean get() = profiles.any { it.enabled && it.role == ModelRole.CODER }
    }

    private val container = (application as ApkBuilderApp).container
    private val git: GitRepository = container.gitRepository
    private val projects: ProjectsRepository = container.projectsRepository
    private val localStore: LocalProjectStore = container.localProjectStore
    private val aiStore: AiProfilesStore = container.aiProfilesStore

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
    /** Remembered file contents so re-opening doesn't hit GitHub every time. */
    private val fileCache = LinkedHashMap<String, String>()
    /** Sticky fallback chain — survives between messages, rebuilt on config change. */
    private var cachedChain: FallbackChatApi? = null
    private var cachedChainSig: String? = null

    private fun chatApiFor(chain: List<ModelProfile>): FallbackChatApi {
        val sig = chain.joinToString("|") {
            "${it.id}:${it.baseUrl}:${it.model}:${it.apiKey.takeLast(6)}"
        }
        if (cachedChainSig != sig || cachedChain == null) {
            cachedChain = FallbackChatApi(
                clients = chain.map { ProviderChatClient(it) },
                labels = chain.map { it.summary },
            )
            cachedChainSig = sig
        }
        return cachedChain!!
    }

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
                val draft = dirtyContents[path]
                val content: String
                var binary = false
                if (draft != null) {
                    content = draft
                } else if (fileCache.containsKey(path)) {
                    content = fileCache[path]!!
                } else {
                    val bytes = git.readFile(owner, repo, branch, path)
                    if (bytes.contains(0.toByte())) {
                        binary = true
                        content = ""
                    } else {
                        content = String(bytes, Charsets.UTF_8)
                        fileCache[path] = content
                        while (fileCache.size > 20) fileCache.remove(fileCache.keys.first())
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

    /**
     * Swaps a binary file (image, jar…) for a newly picked one — committed
     * straight to GitHub after the user confirmed, since binaries can't be
     * edited as text drafts.
     */
    fun replaceBinaryFile(path: String, uri: Uri) {
        ProjectFiles.criticalReason(path)?.let { reason ->
            _state.update { it.copy(message = reason) }
            return
        }
        if (_state.value.saving) return
        viewModelScope.launch {
            try {
                _state.update { it.copy(saving = true) }
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: error("ফাইলটা পড়া গেল না")
                }
                if (bytes.size > 10 * 1024 * 1024) {
                    error("ফাইলটা খুব বড় (${bytes.size / 1048576}MB) — 10MB পর্যন্ত চলে")
                }
                git.pushFiles(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    files = listOf(TemplateRenderer.RenderedFile(path, bytes)),
                    deletions = emptyList(),
                    message = "Replace ${path.substringAfterLast('/')} (from APK Builder)",
                )
                dirtyContents.remove(path)
                fileCache.remove(path)
                withContext(Dispatchers.IO) { localStore.removeDirtyFile(owner, repo, path) }
                // Refresh the sha map so future deletes keep working.
                runCatching {
                    val entries = git.listFiles(owner, repo, branch)
                    serverPaths = entries.map { it.path }
                    shasByPath = entries.associate { it.path to it.sha }
                }
                _state.update {
                    it.copy(saving = false, selectedPath = null, message = "$path বদলে গেছে ✓")
                }
                _selectedFile.value = null
                select(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(saving = false, message = "বদলানো যায়নি: ${e.friendlyMessage()}")
                }
            }
        }
    }

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
            val hadLocalEdits = dirtyContents.containsKey(path) &&
                dirtyContents[path] != content
            dirtyContents[path] = content
            fileCache.remove(path)
            withContext(Dispatchers.IO) { localStore.markDirty(owner, repo, path, content) }
            if (hadLocalEdits) {
                _agent.update { s ->
                    s.copy(
                        messages = s.messages + bubble(
                            false,
                            "⚠ $path-এ তোমার unsaved লেখা ছিল — agent-এর নতুন সংস্করণটা বসানো হলো। আগেরটা ফিরিয়ে আনতে Build আগের সংস্করণ নাও (editor-এ Undo নেই এখনো)",
                            AgentBubble.Kind.TOOL,
                        )
                    )
                }
            }
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

    /** DataStore reads must never crash the app — log and degrade instead. */
    private suspend fun safeProfiles(): List<ModelProfile> = try {
        aiStore.profiles()
    } catch (t: Throwable) {
        AiDebugLog.error("setup", "model profiles পড়া ব্যর্থ", t)
        emptyList()
    }

    private suspend fun safeSkills(): List<Skill> = try {
        aiStore.skills()
    } catch (t: Throwable) {
        AiDebugLog.error("setup", "skills পড়া ব্যর্থ", t)
        emptyList()
    }

    fun openAgent() {
        viewModelScope.launch {
            _agent.update {
                it.copy(
                    open = true,
                    profiles = safeProfiles(),
                    skills = safeSkills(),
                )
            }
            refreshDebug()
        }
    }

    fun closeAgent() = _agent.update { it.copy(open = false) }

    /** Saves a new model profile, then immediately verifies it with /models. */
    fun addProfile(providerId: String, baseUrl: String, apiKey: String, model: String, role: ModelRole) {
        viewModelScope.launch {
            try {
                val profile = aiStore.addProfile(providerId, baseUrl, apiKey, model, role)
                _agent.update {
                    it.copy(
                        profiles = safeProfiles(),
                        notice = "Model যোগ হয়েছে — টেস্ট চলছে…",
                    )
                }
                testProfile(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "model যোগ করা ব্যর্থ", t)
                _agent.update { it.copy(notice = t.friendlyMessage()) }
            } finally {
                refreshDebug()
            }
        }
    }

    fun updateProfile(profile: ModelProfile) {
        viewModelScope.launch {
            try {
                aiStore.updateProfile(profile)
                _agent.update { it.copy(profiles = safeProfiles()) }
                testProfile(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "model edit ব্যর্থ", t)
                _agent.update { it.copy(notice = t.friendlyMessage()) }
            } finally {
                refreshDebug()
            }
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            try {
                aiStore.deleteProfile(id)
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "model মুছতে ব্যর্থ", t)
            }
            _agent.update { it.copy(profiles = safeProfiles()) }
            refreshDebug()
        }
    }

    fun setProfileEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                aiStore.setProfileEnabled(id, enabled)
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "model toggle ব্যর্থ", t)
            }
            _agent.update { it.copy(profiles = safeProfiles()) }
            refreshDebug()
        }
    }

    fun setRole(id: Long, role: ModelRole) {
        viewModelScope.launch {
            try {
                aiStore.setRole(id, role)
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "role বদলাতে ব্যর্থ", t)
            }
            _agent.update { it.copy(profiles = safeProfiles()) }
            refreshDebug()
        }
    }

    /** Loads the provider's model list for the add/edit dialog. */
    fun loadModels(baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            try {
                _agent.update { it.copy(modelsLoading = true) }
                val models = ProviderChatClient(baseUrl, apiKey).listModels()
                _agent.update {
                    it.copy(
                        modelsLoading = false,
                        models = models,
                        notice = if (models.isEmpty()) "কোনো model পাওয়া যায়নি" else "${models.size} models লোড হয়েছে",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "models লোড ব্যর্থ", t)
                _agent.update { it.copy(modelsLoading = false, notice = t.friendlyMessage()) }
            } finally {
                refreshDebug()
            }
        }
    }

    fun clearLoadedModels() = _agent.update { it.copy(models = emptyList()) }

    private suspend fun testProfile(profile: ModelProfile) {
        _agent.update { it.copy(testingProfileId = profile.id, profileTests = it.profileTests - profile.id) }
        val result = try {
            val models = ProviderChatClient(profile.baseUrl, profile.apiKey).listModels()
            when {
                models.isEmpty() -> ProfileTest(ok = true, message = "সংযোগ ঠিক, কিন্তু model list খালি")
                models.contains(profile.model) ->
                    ProfileTest(ok = true, message = "কাজ করছে ✓ (${models.size} models)")
                else -> ProfileTest(
                    ok = true,
                    message = "সংযোগ ঠিক ✓ — কিন্তু \"${profile.model}\" list-এ নেই, id যাচাই করো",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AiDebugLog.error("setup", "টেস্ট ব্যর্থ: ${profile.summary}", t)
            ProfileTest(ok = false, message = t.friendlyMessage())
        }
        _agent.update {
            it.copy(
                testingProfileId = null,
                profileTests = it.profileTests + (profile.id to result),
                notice = if (result.ok) result.message else "টেস্ট ব্যর্থ: ${result.message}",
            )
        }
    }

    fun addSkill(name: String, instructions: String) {
        viewModelScope.launch {
            try {
                aiStore.addSkill(name, instructions)
                _agent.update { it.copy(skills = safeSkills(), notice = "Skill যোগ হয়েছে") }
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "skill যোগ করা ব্যর্থ", t)
                _agent.update { it.copy(notice = t.friendlyMessage()) }
            }
        }
    }

    fun toggleSkill(id: Long) {
        viewModelScope.launch {
            try {
                aiStore.toggleSkill(id)
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "skill toggle ব্যর্থ", t)
            }
            _agent.update { it.copy(skills = safeSkills()) }
        }
    }

    fun deleteSkill(id: Long) {
        viewModelScope.launch {
            try {
                aiStore.deleteSkill(id)
            } catch (t: Throwable) {
                AiDebugLog.error("setup", "skill মুছতে ব্যর্থ", t)
            }
            _agent.update { it.copy(skills = safeSkills()) }
        }
    }

    // -------------------------------------------------------------- debug --

    /** Copies the current AI debug log into the UI state (🐞 pane). */
    fun refreshDebug() {
        _agent.update { it.copy(debugEntries = AiDebugLog.snapshot()) }
    }

    fun clearDebug() {
        AiDebugLog.clear()
        refreshDebug()
    }

    /**
     * End-to-end connection check for the 🐞 pane: hits /models, then sends
     * one tiny chat message (no tools). Every step is visible in the log.
     */
    fun testCoderConnection() {
        val coder = _agent.value.profiles.firstOrNull { it.enabled && it.role == ModelRole.CODER }
            ?: _agent.value.profiles.firstOrNull { it.enabled }
        if (coder == null) {
            _agent.update { it.copy(notice = "আগে ⚙ setup-এ একটা model যোগ করো") }
            return
        }
        viewModelScope.launch {
            _agent.update { it.copy(testRunning = true) }
            AiDebugLog.info("test", "টেস্ট শুরু: ${coder.summary}")
            try {
                val client = ProviderChatClient(coder)
                val models = client.listModels()
                val response = client.chat(
                    ChatRequest(
                        model = coder.model,
                        messages = listOf(
                            ChatMessage(role = "user", content = "Reply with exactly: OK")
                        ),
                    )
                )
                val text = response.choices.firstOrNull()?.message?.content
                if (text.isNullOrBlank()) {
                    AiDebugLog.error("test", "chat উত্তর এসেছে কিন্তু content খালি — model টা কাজ করছে না")
                    _agent.update {
                        it.copy(testRunning = false, notice = "Chat খালি উত্তর দিলো — বিস্তারিত 🐞 এ")
                    }
                } else {
                    AiDebugLog.ok("test", "সংযোগ সম্পূর্ণ ঠিক ✓ (chat উত্তর: \"${text.take(60)}\")")
                    _agent.update {
                        it.copy(testRunning = false, notice = "সংযোগ ঠিক ✓ (${coder.model})")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiDebugLog.error("test", "টেস্ট ব্যর্থ", t)
                _agent.update { it.copy(testRunning = false, notice = "টেস্ট ব্যর্থ: ${t.friendlyMessage()}") }
            } finally {
                refreshDebug()
            }
        }
    }

    // -------------------------------------------------------------- chat --

    /**
     * Opens the agent with a build-failure prompt (from the "Fix with AI"
     * button). With a coder model configured, the fix starts right away;
     * otherwise the sheet opens on the setup screen with the prompt queued.
     */
    fun seedAgentInput(prompt: String) {
        viewModelScope.launch {
            val profiles = safeProfiles()
            _agent.update {
                it.copy(
                    open = true,
                    profiles = profiles,
                    skills = safeSkills(),
                )
            }
            when {
                _agent.value.busy -> _agent.update {
                    it.copy(pendingInput = prompt, notice = "Agent ব্যস্ত ছিল — prompt ইনপুট বক্সে বসানো হলো")
                }
                profiles.any { p -> p.enabled && p.role == ModelRole.CODER } ->
                    sendAgentMessage(prompt, _state.value.selectedPath, null)
                else -> _agent.update { it.copy(pendingInput = prompt) }
            }
        }
    }

    fun consumePendingInput() = _agent.update { it.copy(pendingInput = null) }

    fun consumeAgentNotice() = _agent.update { it.copy(notice = null) }

    fun stopAgent() {
        agentJob?.cancel()
    }

    fun clearChat() {
        agentHistory.clear()
        _agent.update { it.copy(messages = emptyList(), streamingBubbleId = null) }
    }

    private fun finalizeStreamingBubble() {
        _agent.update { s ->
            if (s.streamingBubbleId == null) s
            else s.copy(
                streamingBubbleId = null,
                messages = s.messages.map {
                    if (it.id == s.streamingBubbleId) it.copy(streaming = false) else it
                },
            )
        }
    }

    fun sendAgentMessage(message: String, selectedPath: String?, selectedCode: String?) {
        val text = message.trim()
        if (text.isEmpty() || _agent.value.busy) return
        val enabled = _agent.value.profiles.filter { it.enabled }
        val coder = enabled.firstOrNull { it.role == ModelRole.CODER }
        if (coder == null) {
            _agent.update { it.copy(notice = "আগে ⚙ setup-এ একটা Coder model যোগ করো") }
            return
        }
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            _agent.update {
                it.copy(busy = true, messages = it.messages + bubble(fromUser = true, text = text))
            }
            AiDebugLog.info("chat", "message পাঠানো হলো (${text.length} chars), coder=${coder.summary}")
            try {
                val reviewer = enabled.firstOrNull { it.role == ModelRole.REVIEWER }
                val fallbacks = enabled.filter { it.role == ModelRole.FALLBACK }
                // The coder chain: coder + fallbacks (NOT the reviewer — it
                // must stay a reviewer, not a backup coder).
                val chain = listOf(coder) + fallbacks
                val chat = chatApiFor(chain)
                val agent = MultiModelAgent(
                    chat = chat,
                    reviewer = reviewer?.let { ProviderChatClient(it) },
                    reviewerModel = reviewer?.model,
                )
                val system = buildSystemPrompt(selectedPath, selectedCode)
                var streamedAny = false
                val result = agent.run(
                    model = coder.model,
                    systemPrompt = system,
                    history = agentHistory.toList(),
                    userMessage = text,
                    project = projectAccess,
                ) { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            streamedAny = true
                            _agent.update { s ->
                            val sid = s.streamingBubbleId
                            if (sid == null) {
                                val b = bubble(false, event.text, streaming = true)
                                s.copy(messages = s.messages + b, streamingBubbleId = b.id)
                            } else {
                                s.copy(messages = s.messages.map {
                                    if (it.id == sid) it.copy(text = it.text + event.text) else it
                                })
                            }
                            }
                        }
                        is AgentEvent.AssistantText -> {
                            finalizeStreamingBubble()
                            _agent.update { s ->
                                s.copy(messages = s.messages + bubble(false, event.text))
                            }
                        }
                        is AgentEvent.ToolActivity -> {
                            finalizeStreamingBubble()
                            _agent.update { s ->
                                s.copy(messages = s.messages + bubble(false, event.label, AgentBubble.Kind.TOOL))
                            }
                        }
                    }
                }
                finalizeStreamingBubble()
                val finalText = result.finalText
                    ?: "ধাপ সীমা শেষ — এখন পর্যন্ত যা হয়েছে দেখে আবার বলো।"
                if (result.finalText != null) {
                    agentHistory += ChatMessage(role = "user", content = text)
                    agentHistory += ChatMessage(role = "assistant", content = finalText)
                    // Keep the conversation bounded: newest 16 messages stay.
                    while (agentHistory.size > 16) agentHistory.removeAt(0)
                }
                AiDebugLog.ok("chat", "কাজ শেষ (${result.steps} steps)")
                _agent.update {
                    it.copy(
                        busy = false,
                        messages = it.messages + buildList {
                            if (!streamedAny) add(bubble(false, finalText))
                            add(bubble(false, "বদলগুলো draft হিসেবে এডিটরে বসেছে — Save → Build চেপে দেখো", AgentBubble.Kind.TOOL))
                        },
                    )
                }
            } catch (e: CancellationException) {
                _agent.update {
                    it.copy(
                        busy = false,
                        messages = it.messages + bubble(false, "থামানো হলো", AgentBubble.Kind.TOOL),
                    )
                }
            } catch (t: Throwable) {
                AiDebugLog.error("chat", "কাজ ব্যর্থ হয়েছে", t)
                _agent.update {
                    it.copy(
                        busy = false,
                        messages = it.messages + bubble(false, t.friendlyMessage(), AgentBubble.Kind.ERROR),
                    )
                }
            } finally {
                refreshDebug()
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

        val skills = safeSkills().filter { it.enabled && it.instructions.isNotBlank() }

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
            if (skills.isNotEmpty()) {
                appendLine()
                appendLine("Skill guides the user enabled — follow them:")
                skills.forEach { appendLine("- ${it.name}: ${it.instructions}") }
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

    private fun bubble(
        fromUser: Boolean,
        text: String,
        kind: AgentBubble.Kind = AgentBubble.Kind.TEXT,
        streaming: Boolean = false,
    ) = AgentBubble(id = ++bubbleId, fromUser = fromUser, text = text, kind = kind, streaming = streaming)
}
