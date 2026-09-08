package com.enajid.apkbuilder.ui.createproject

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.ProjectCreator
import com.enajid.apkbuilder.data.TemplateRenderer
import com.enajid.apkbuilder.data.friendlyMessage
import com.enajid.apkbuilder.domain.Framework
import com.enajid.apkbuilder.domain.PackageNames
import com.enajid.apkbuilder.domain.ProjectSpec
import com.enajid.apkbuilder.util.IconUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CreateUiState(
    val login: String? = null,
    val appName: String = "",
    val packageName: String = "",
    val packageEdited: Boolean = false,
    val minSdk: Int = 24,
    val targetSdk: Int = 34,
    val framework: Framework = Framework.KOTLIN,
    val iconBytes: ByteArray? = null,
    val creating: Boolean = false,
    val creatingStep: ProjectCreator.Step? = null,
    val error: String? = null,
    /** owner to repo of the freshly created project — triggers navigation. */
    val created: Pair<String, String>? = null,
)

class CreateProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ApkBuilderApp).container
    private val projectCreator = container.projectCreator
    private val tokenStore = container.tokenStore

    private val _state = MutableStateFlow(CreateUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val login = withContext(Dispatchers.IO) { tokenStore.loginFlow.first() }
            _state.update { it.copy(login = login) }
        }
    }

    fun setAppName(value: String) {
        _state.update {
            it.copy(
                appName = value,
                packageName = if (it.packageEdited) it.packageName
                else PackageNames.suggestFromAppName(value),
            )
        }
    }

    fun setPackageName(value: String) {
        _state.update { it.copy(packageName = value, packageEdited = true) }
    }

    fun setMinSdk(value: Int) {
        _state.update { it.copy(minSdk = value, targetSdk = maxOf(value, it.targetSdk)) }
    }

    fun setTargetSdk(value: Int) {
        _state.update { it.copy(targetSdk = value) }
    }

    fun setFramework(framework: Framework) {
        if (framework.available) _state.update { it.copy(framework = framework) }
    }

    fun onIconPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { IconUtils.loadIconPng(getApplication(), uri) }
                if (bytes != null) _state.update { it.copy(iconBytes = bytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't read that image: ${e.friendlyMessage()}") }
            }
        }
    }

    fun clearIcon() = _state.update { it.copy(iconBytes = null) }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun onNavigated() = _state.update { it.copy(created = null) }

    fun create() {
        val current = _state.value
        when {
            current.creating -> return
            current.appName.isBlank() -> _state.update { it.copy(error = "Give your app a name first") }
            !PackageNames.isValid(current.packageName) ->
                _state.update { it.copy(error = "The package name isn't valid yet") }
            current.targetSdk < current.minSdk ->
                _state.update { it.copy(error = "Target SDK can't be lower than the minimum SDK") }
            !current.framework.available ->
                _state.update { it.copy(error = "That framework is coming soon — pick Kotlin for now") }
            else -> viewModelScope.launch {
                _state.update {
                    it.copy(creating = true, error = null, creatingStep = ProjectCreator.Step.CREATING_REPO)
                }
                try {
                    val spec = ProjectSpec(
                        appName = current.appName.trim(),
                        packageName = current.packageName.trim(),
                        minSdk = current.minSdk,
                        targetSdk = current.targetSdk,
                        framework = current.framework,
                        iconPng = current.iconBytes,
                    )
                    val repo = projectCreator.createKotlinProject(spec) { step ->
                        _state.update { it.copy(creatingStep = step) }
                    }
                    _state.update {
                        it.copy(
                            creating = false,
                            created = (repo.owner?.login ?: "") to repo.name,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Say WHERE it failed so the user isn't left guessing.
                    val where = when (_state.value.creatingStep) {
                        ProjectCreator.Step.CREATING_REPO -> "while creating the GitHub repository"
                        ProjectCreator.Step.UPLOADING_CODE -> "while uploading the project files"
                        ProjectCreator.Step.FINISHING, null -> "while finishing up"
                    }
                    _state.update {
                        it.copy(
                            creating = false,
                            error = "Failed $where: ${e.friendlyMessage()} " +
                                "Tap “Create app” to try again — it's safe to retry.",
                        )
                    }
                }
            }
        }
    }

    /** Shown under the app name so users know where the code will live. */
    fun suggestedRepoName(): String =
        TemplateRenderer.repoNameFromAppName(_state.value.appName)
}
