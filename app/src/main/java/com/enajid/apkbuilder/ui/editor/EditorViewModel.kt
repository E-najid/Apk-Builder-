package com.enajid.apkbuilder.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.GitRepository
import com.enajid.apkbuilder.data.LocalProjectStore
import com.enajid.apkbuilder.data.ProjectsRepository
import com.enajid.apkbuilder.data.TemplateRenderer
import com.enajid.apkbuilder.data.friendlyMessage
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

    data class LoadedFile(val path: String, val content: String, val binary: Boolean)

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
        val message: String? = null,
    )

    private val container = (application as ApkBuilderApp).container
    private val git: GitRepository = container.gitRepository
    private val projects: ProjectsRepository = container.projectsRepository
    private val localStore: LocalProjectStore = container.localProjectStore

    private val owner: String = savedStateHandle.get<String>("owner") ?: ""
    private val repo: String = savedStateHandle.get<String>("repo") ?: ""

    private val _state = MutableStateFlow(EditorUiState())
    val state = _state.asStateFlow()

    private val _selectedFile = MutableStateFlow<LoadedFile?>(null)
    val selectedFile = _selectedFile.asStateFlow()

    private var serverPaths: List<String> = emptyList()
    private val dirtyContents = LinkedHashMap<String, String>()
    private var branch: String = "main"
    private var debounce: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, error = null) }
                val repoInfo = projects.getRepo(owner, repo)
                branch = repoInfo.default_branch.ifBlank { "main" }
                val entries = git.listFiles(owner, repo, branch)
                serverPaths = entries.map { it.path }
                val localDirty = withContext(Dispatchers.IO) { localStore.loadDirty(owner, repo) }
                dirtyContents.clear()
                dirtyContents.putAll(localDirty)
                _state.update {
                    it.copy(
                        loading = false,
                        repoName = repoInfo.name,
                        branch = branch,
                        paths = (serverPaths + dirtyContents.keys).sorted(),
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
                    it.copy(fileLoading = false, message = "Couldn't open the file: ${e.friendlyMessage()}")
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
            _state.update { it.copy(saving = false, message = "Couldn't save: ${e.friendlyMessage()}") }
            false
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
                paths = (serverPaths + dirtyContents.keys).sorted(),
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
}
