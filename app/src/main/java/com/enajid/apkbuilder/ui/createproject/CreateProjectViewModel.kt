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
import com.enajid.apkbuilder.domain.ProjectSource
import com.enajid.apkbuilder.domain.ProjectSpec
import com.enajid.apkbuilder.domain.ZipProjectScanner
import com.enajid.apkbuilder.util.IconUtils
import com.enajid.apkbuilder.util.ImportOutcome
import com.enajid.apkbuilder.util.ZipProjectImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CreateUiState(
    val login: String? = null,
    val source: ProjectSource = ProjectSource.SCRATCH,
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
    // --- zip upload flow ---
    val zipScanning: Boolean = false,
    /** Short summary of what was detected, shown above the form. */
    val zipSummary: String? = null,
    val zipFileCount: Int = 0,
    val isGradleProject: Boolean = true,
    val hasOwnWorkflow: Boolean = false,
    /** True when the zip contains its own build.yml and the user must choose. */
    val pendingWorkflowChoice: Boolean = false,
    /** Short note for frameworks whose cloud build isn't v1 (Flutter/RN/Java). */
    val frameworkNotice: String? = null,
)

class CreateProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ApkBuilderApp).container
    private val projectCreator = container.projectCreator
    private val tokenStore = container.tokenStore

    private val _state = MutableStateFlow(CreateUiState())
    val state = _state.asStateFlow()

    private var uploadRootDir: File? = null
    private var workflowKeep: Boolean? = null

    init {
        viewModelScope.launch {
            val login = withContext(Dispatchers.IO) { tokenStore.loginFlow.first() }
            _state.update { it.copy(login = login) }
        }
    }

    override fun onCleared() {
        ZipProjectImporter.cleanup(uploadRootDir)
        uploadRootDir = null
        super.onCleared()
    }

    fun setSource(source: ProjectSource) {
        _state.update { it.copy(source = source) }
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
        // Scratch projects need a bundled template; uploads accept anything
        // (with a matching build workflow injected when the zip has none).
        if (_state.value.source == ProjectSource.SCRATCH && !framework.available) return
        _state.update { it.copy(framework = framework) }
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

    // ------------------------------------------------------------------ zip --

    fun onZipPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(zipScanning = true, error = null, zipSummary = null) }
                val outcome = withContext(Dispatchers.IO) {
                    ZipProjectImporter.import(getApplication(), uri)
                }
                when (outcome) {
                    is ImportOutcome.TooLarge -> _state.update {
                        it.copy(
                            zipScanning = false,
                            error = "That zip is ${(outcome.sizeBytes / 1_048_576) + 1} MB — the " +
                                "limit is 50 MB. Remove build outputs (build/, .gradle/) and try again.",
                        )
                    }
                    is ImportOutcome.Failed -> _state.update {
                        it.copy(zipScanning = false, error = outcome.message)
                    }
                    is ImportOutcome.Success -> applyScan(outcome)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(zipScanning = false, error = e.friendlyMessage()) }
            }
        }
    }

    private fun applyScan(outcome: ImportOutcome.Success) {
        val scan = ZipProjectScanner.scan(outcome.scanFiles)
        if (!scan.valid) {
            ZipProjectImporter.cleanup(outcome.rootDir)
            _state.update {
                it.copy(zipScanning = false, error = scan.invalidReason ?: "The zip couldn’t be read.")
            }
            return
        }
        // Fresh upload: drop any previous one.
        ZipProjectImporter.cleanup(uploadRootDir)
        uploadRootDir = outcome.rootDir
        workflowKeep = null

        val detectedFramework = scan.framework
        val notice = when (detectedFramework) {
            Framework.FLUTTER ->
                "Flutter detected. Your code is pushed as-is; if the zip has no build " +
                    "workflow, APK Builder adds a Flutter one."
            Framework.REACT_NATIVE ->
                "React Native detected. Your code is pushed as-is; if the zip has no build " +
                    "workflow, APK Builder adds a React Native one."
            else -> if (!scan.isGradleProject) {
                "This doesn’t look like a standard Gradle project — the cloud build may " +
                    "not work, but your code will be safely on GitHub."
            } else null
        }

        _state.update {
            it.copy(
                zipScanning = false,
                source = ProjectSource.UPLOAD,
                appName = scan.appName.orEmpty(),
                packageName = scan.packageName.orEmpty(),
                packageEdited = scan.packageName != null,
                minSdk = scan.minSdk ?: 24,
                targetSdk = scan.targetSdk ?: 34,
                framework = detectedFramework ?: Framework.KOTLIN,
                iconBytes = scan.iconBytes,
                zipFileCount = outcome.fileCount,
                isGradleProject = scan.isGradleProject,
                hasOwnWorkflow = scan.hasOwnWorkflow,
                pendingWorkflowChoice = false,
                frameworkNotice = notice,
                zipSummary = buildString {
                    append("Detected: ")
                    append(detectedFramework?.label ?: "unknown framework")
                    if (scan.isGradleProject) append(" · Gradle project")
                    append(" · ${outcome.fileCount} files")
                },
            )
        }
    }

    // -------------------------------------------------------------- creation --

    fun onWorkflowChoice(keep: Boolean) {
        workflowKeep = keep
        _state.update { it.copy(pendingWorkflowChoice = false) }
        create()
    }

    fun create() {
        val current = _state.value
        when {
            current.creating -> return
            current.appName.isBlank() -> _state.update { it.copy(error = "Give your app a name first") }
            !PackageNames.isValid(current.packageName) ->
                _state.update { it.copy(error = "The package name isn't valid yet") }
            current.targetSdk < current.minSdk ->
                _state.update { it.copy(error = "Target SDK can't be lower than the minimum SDK") }
            current.source == ProjectSource.SCRATCH && !current.framework.available ->
                _state.update { it.copy(error = "That framework is coming soon — pick Kotlin for now") }
            current.source == ProjectSource.UPLOAD && uploadRootDir == null ->
                _state.update { it.copy(error = "Choose a project zip first") }
            current.source == ProjectSource.UPLOAD &&
                current.hasOwnWorkflow && workflowKeep == null ->
                _state.update { it.copy(pendingWorkflowChoice = true) }
            else -> viewModelScope.launch {
                _state.update {
                    it.copy(
                        creating = true,
                        error = null,
                        pendingWorkflowChoice = false,
                        creatingStep = ProjectCreator.Step.CREATING_REPO,
                    )
                }
                try {
                    val spec = ProjectSpec(
                        appName = current.appName.trim(),
                        packageName = current.packageName.trim(),
                        minSdk = current.minSdk,
                        targetSdk = current.targetSdk,
                        framework = current.framework,
                        iconPng = if (current.source == ProjectSource.SCRATCH) current.iconBytes else null,
                    )
                    val repo = when (current.source) {
                        ProjectSource.SCRATCH ->
                            projectCreator.createKotlinProject(spec) { step ->
                                _state.update { it.copy(creatingStep = step) }
                            }
                        ProjectSource.UPLOAD -> {
                            val root = requireNotNull(uploadRootDir) { "No uploaded project" }
                            val files = withContext(Dispatchers.IO) {
                                ZipProjectImporter.buildPushFiles(root)
                            }
                            val useOwnWorkflow = current.hasOwnWorkflow && workflowKeep == true
                            projectCreator.createProjectFromZip(
                                spec,
                                files,
                                useOwnWorkflow = useOwnWorkflow,
                            ) { step ->
                                _state.update { it.copy(creatingStep = step) }
                            }
                        }
                    }
                    ZipProjectImporter.cleanup(uploadRootDir)
                    uploadRootDir = null
                    _state.update {
                        it.copy(
                            creating = false,
                            created = (repo.owner?.login ?: "") to repo.name,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
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
        TemplateRenderer.repoNameFromAppName(_state.value.appName.ifBlank { "android-project" })
}
