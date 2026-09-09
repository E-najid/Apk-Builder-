package com.enajid.apkbuilder.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.GithubRepo
import com.enajid.apkbuilder.data.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

data class HomeUiState(
    val login: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val projects: List<GithubRepo> = emptyList(),
    val error: String? = null,
    /** Repo currently being deleted (its GitHub id), for per-card progress. */
    val deletingRepoId: Long? = null,
    /** True when deletion failed because the token lacks the delete permission. */
    val reauthNeeded: Boolean = false,
    val message: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ApkBuilderApp).container
    private val projectsRepository = container.projectsRepository
    private val authRepository = container.authRepository
    private val tokenStore = container.tokenStore

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(showAsRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                _state.update {
                    if (showAsRefresh) it.copy(refreshing = true, error = null)
                    else it.copy(loading = true, error = null)
                }
                val login = withContext(Dispatchers.IO) { tokenStore.loginFlow.first() }
                val projects = projectsRepository.listProjects()
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        projects = projects,
                        login = login ?: it.login,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.friendlyMessage()) }
            }
        }
    }

    /**
     * Permanently deletes the project's GitHub repository. The card stays in
     * the list unless the API call succeeds — no optimistic UI for
     * destructive actions.
     */
    fun deleteProject(project: GithubRepo) {
        if (_state.value.deletingRepoId != null) return
        viewModelScope.launch {
            try {
                _state.update { it.copy(deletingRepoId = project.id) }
                val owner = project.owner?.login ?: project.full_name.substringBefore('/')
                projectsRepository.deleteProject(owner, project.name)
                _state.update {
                    it.copy(
                        deletingRepoId = null,
                        projects = it.projects.filterNot { p -> p.id == project.id },
                        message = "Deleted “${project.name}”",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when {
                    // 404 with an existing repo = token lacks delete_repo;
                    // 404 with a gone repo = already deleted on github.com.
                    e.code() == 404 -> {
                        val owner = project.owner?.login ?: project.full_name.substringBefore('/')
                        val stillExists =
                            runCatching { projectsRepository.getRepo(owner, project.name) }.getOrNull()
                        if (stillExists != null) {
                            _state.update { it.copy(deletingRepoId = null, reauthNeeded = true) }
                        } else {
                            _state.update {
                                it.copy(
                                    deletingRepoId = null,
                                    projects = it.projects.filterNot { p -> p.id == project.id },
                                    message = "“${project.name}” was already gone from GitHub",
                                )
                            }
                        }
                    }
                    e.code() == 403 -> _state.update { it.copy(deletingRepoId = null, reauthNeeded = true) }
                    else -> _state.update {
                        it.copy(deletingRepoId = null, message = "Couldn’t delete: ${e.friendlyMessage()}")
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(deletingRepoId = null, message = "Couldn’t delete: ${e.friendlyMessage()}") }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun dismissReauth() = _state.update { it.copy(reauthNeeded = false) }

    fun signOut() {
        viewModelScope.launch {
            try {
                authRepository.signOut()
            } catch (_: Exception) {
                // Nothing sensible to do — the token stays for the next attempt.
            }
        }
    }
}
