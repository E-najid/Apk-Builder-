package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.Toolchain
import com.enajid.apkbuilder.domain.ToolchainDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Kicks off a build:
 *  1. commits any unsaved editor changes (or, if there are none, creates an
 *     empty commit so the workflow's `push` trigger still fires),
 *  2. waits for GitHub Actions to register a run for that commit,
 *  3. falls back to a `workflow_dispatch` if no run appears in time.
 */
class BuildOrchestrator(
    private val gitRepository: GitRepository,
    private val actionsRepository: ActionsRepository,
    private val localProjectStore: LocalProjectStore,
) {

    data class TriggerResult(
        val commitSha: String,
        val toolchains: Set<Toolchain>,
    )

    suspend fun trigger(owner: String, repo: String, branch: String): TriggerResult {
        val dirty = withContext(Dispatchers.IO) { localProjectStore.loadDirty(owner, repo) }
        val paths = gitRepository.listFiles(owner, repo, branch).map { it.path } + dirty.keys
        val toolchains = ToolchainDetector.detect(paths)

        val commitSha = if (dirty.isNotEmpty()) {
            val files = dirty.map { (path, content) ->
                TemplateRenderer.RenderedFile(path, content.toByteArray(Charsets.UTF_8))
            }
            gitRepository.pushFiles(
                owner = owner,
                repo = repo,
                branch = branch,
                files = files,
                deletions = emptyList(),
                message = "Update code from APK Builder",
            )
        } else {
            gitRepository.createEmptyCommit(
                owner = owner,
                repo = repo,
                branch = branch,
                message = "Trigger build from APK Builder",
            )
        }
        return TriggerResult(commitSha = commitSha, toolchains = toolchains)
    }

    /**
     * Polls for a workflow run belonging to [commitSha]. If GitHub hasn't
     * picked the push up after ~45s we nudge it with a manual dispatch.
     * Returns null if nothing appears within ~90s.
     */
    suspend fun waitForRun(
        owner: String,
        repo: String,
        branch: String,
        commitSha: String,
    ): WorkflowRun? {
        val startedAt = System.currentTimeMillis()
        var dispatched = false
        while (System.currentTimeMillis() - startedAt < TOTAL_TIMEOUT_MS) {
            delay(POLL_INTERVAL_MS)
            actionsRepository.findRunForSha(owner, repo, commitSha)?.let { return it }
            val elapsed = System.currentTimeMillis() - startedAt
            if (!dispatched && elapsed > DISPATCH_AFTER_MS) {
                try {
                    actionsRepository.dispatch(owner, repo, branch)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The push trigger usually wins; ignore dispatch problems for now.
                }
                dispatched = true
            }
        }
        return null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
        private const val DISPATCH_AFTER_MS = 45_000L
        private const val TOTAL_TIMEOUT_MS = 90_000L
    }
}
