package com.enajid.apkbuilder.ui.build

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.ActionsRepository
import com.enajid.apkbuilder.data.JobInfo
import com.enajid.apkbuilder.data.ProjectsRepository
import com.enajid.apkbuilder.data.WorkflowRun
import com.enajid.apkbuilder.data.friendlyMessage
import com.enajid.apkbuilder.domain.LogSummarizer
import com.enajid.apkbuilder.domain.MappedStep
import com.enajid.apkbuilder.domain.StepMapper
import com.enajid.apkbuilder.util.ApkFileUtils
import com.enajid.apkbuilder.util.TimeUtils
import com.enajid.apkbuilder.util.ZipUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class BuildStatusViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    enum class Phase { PUSHING, WAITING, RUNNING, SUCCESS, FAILURE, ERROR }

    data class DownloadedApk(
        val file: File,
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val savedToDownloads: Boolean,
    )

    data class BuildUiState(
        val phase: Phase = Phase.PUSHING,
        val repoName: String = "",
        val runId: Long? = null,
        val runUrl: String? = null,
        val runNumber: Int? = null,
        val elapsedMs: Long? = null,
        val steps: List<MappedStep> = emptyList(),
        val slowBuildNote: String? = null,
        val failureHeading: String? = null,
        val failureLines: List<String> = emptyList(),
        val artifactId: Long? = null,
        val artifactName: String? = null,
        val artifactSizeBytes: Long? = null,
        val artifactExpired: Boolean = false,
        val downloading: Boolean = false,
        val downloaded: DownloadedApk? = null,
        val error: String? = null,
    )

    private val container = (application as ApkBuilderApp).container
    private val orchestrator = container.buildOrchestrator
    private val actions: ActionsRepository = container.actionsRepository
    private val projects: ProjectsRepository = container.projectsRepository

    private val owner: String = savedStateHandle.get<String>("owner") ?: ""
    private val repo: String = savedStateHandle.get<String>("repo") ?: ""

    private val _state = MutableStateFlow(BuildUiState(repoName = repo))
    val state = _state.asStateFlow()

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            try {
                _state.value = BuildUiState(repoName = repo)
                val repoInfo = projects.getRepo(owner, repo)
                val branch = repoInfo.default_branch.ifBlank { "main" }
                _state.update { it.copy(repoName = repoInfo.name) }

                // 1. Commit pending edits (or an empty commit) and detect toolchains.
                val trigger = orchestrator.trigger(owner, repo, branch)
                val slow = trigger.toolchains.any { it.slowsBuildDown }
                val toolchainList = trigger.toolchains.joinToString { it.label }
                _state.update {
                    it.copy(
                        phase = Phase.WAITING,
                        slowBuildNote = when {
                            slow -> "Heads up: this project uses $toolchainList — " +
                                "the build may take longer than usual."
                            trigger.toolchains.isNotEmpty() ->
                                "Detected extra toolchains: $toolchainList."
                            else -> null
                        },
                    )
                }

                // 2. Wait for GitHub Actions to pick up the commit.
                val run = orchestrator.waitForRun(owner, repo, branch, trigger.commitSha)
                if (run == null) {
                    _state.update {
                        it.copy(
                            phase = Phase.ERROR,
                            error = "GitHub didn't start the build within 90 seconds. " +
                                "Check the repo's Actions tab on github.com, then try again.",
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        phase = Phase.RUNNING,
                        runId = run.id,
                        runUrl = run.html_url,
                        runNumber = run.run_number,
                    )
                }

                // 3. Poll run + job steps until the run completes.
                while (true) {
                    delay(5_000)
                    val fresh = actions.getRun(owner, repo, run.id)
                    val jobs = actions.getJobs(owner, repo, run.id)
                    val steps = jobs.firstOrNull()?.let { StepMapper.map(it.steps) } ?: emptyList()
                    _state.update {
                        it.copy(
                            runUrl = fresh.html_url,
                            elapsedMs = elapsedMs(fresh, jobs),
                            steps = steps,
                        )
                    }
                    if (fresh.status == "completed") {
                        if (fresh.conclusion == "success") {
                            val artifacts = actions.getArtifacts(owner, repo, run.id)
                            val artifact = artifacts.firstOrNull { !it.expired } ?: artifacts.firstOrNull()
                            _state.update {
                                it.copy(
                                    phase = Phase.SUCCESS,
                                    artifactId = artifact?.id,
                                    artifactName = artifact?.name,
                                    artifactSizeBytes = artifact?.size_in_bytes,
                                    artifactExpired = artifact != null && artifact.expired,
                                    elapsedMs = elapsedMs(fresh, jobs),
                                )
                            }
                        } else {
                            val (heading, lines) = summarizeFailure(jobs)
                            _state.update {
                                it.copy(
                                    phase = Phase.FAILURE,
                                    failureHeading = heading,
                                    failureLines = lines,
                                    elapsedMs = elapsedMs(fresh, jobs),
                                )
                            }
                        }
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, error = e.friendlyMessage()) }
            }
        }
    }

    /** Fetches the artifact zip, extracts the APK and stages it for install/share. */
    fun downloadApk() {
        val artifactId = _state.value.artifactId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(downloading = true, downloaded = null, error = null) }
                val zip = actions.downloadArtifactZip(owner, repo, artifactId)
                val extracted = ZipUtils.extractFirstApk(zip)
                if (extracted == null) {
                    _state.update {
                        it.copy(downloading = false, error = "The artifact didn't contain an APK.")
                    }
                    return@launch
                }
                val (name, bytes) = extracted
                val context: Context = getApplication()
                val dir = File(context.filesDir, "apks/${repo}-${_state.value.runId}")
                dir.mkdirs()
                val file = File(dir, name)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val savedToDownloads = ApkFileUtils.saveToPublicDownloads(context, name, bytes) != null
                _state.update {
                    it.copy(
                        downloading = false,
                        downloaded = DownloadedApk(
                            file = file,
                            uri = uri,
                            displayName = name,
                            sizeBytes = bytes.size.toLong(),
                            savedToDownloads = savedToDownloads,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(downloading = false, error = e.friendlyMessage()) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun elapsedMs(run: WorkflowRun, jobs: List<JobInfo>): Long? {
        val startIso = jobs.firstOrNull()?.started_at ?: run.created_at
        val start = TimeUtils.parseGitHubTime(startIso) ?: return null
        val end = if (run.status == "completed") {
            TimeUtils.parseGitHubTime(run.updated_at) ?: System.currentTimeMillis()
        } else {
            System.currentTimeMillis()
        }
        return (end - start).coerceAtLeast(0)
    }

    private suspend fun summarizeFailure(jobs: List<JobInfo>): Pair<String, List<String>> {
        val failedJob = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.firstOrNull()
        val failedStep = failedJob?.steps?.lastOrNull { it.conclusion == "failure" }
        val heading = "Failed at: ${failedStep?.name ?: failedJob?.name ?: "unknown step"}"
        val lines = failedJob
            ?.let { actions.jobLogs(owner, repo, it.id) }
            ?.let { LogSummarizer.summarize(it) }
            .orEmpty()
        return heading to lines
    }
}
