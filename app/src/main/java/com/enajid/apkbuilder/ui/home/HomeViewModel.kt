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

data class HomeUiState(
    val login: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val projects: List<GithubRepo> = emptyList(),
    val error: String? = null,
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
