package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.ProjectSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates a new project end-to-end: repo + code + marker topic.
 * The caller only shows a loading state — everything here is invisible to the
 * user, as requested.
 */
class ProjectCreator(
    private val projectsRepository: ProjectsRepository,
    private val gitRepository: GitRepository,
    private val templateEngine: TemplateEngine,
) {

    enum class Step { CREATING_REPO, UPLOADING_CODE, FINISHING }

    suspend fun createKotlinProject(
        spec: ProjectSpec,
        onStep: (Step) -> Unit = {},
    ): GithubRepo = withContext(Dispatchers.IO) {
        onStep(Step.CREATING_REPO)
        val repoName = TemplateRenderer.repoNameFromAppName(spec.appName)
        val repo = projectsRepository.createProjectRepo(
            baseName = repoName,
            description = "${spec.appName} — ${ProjectsRepository.DESCRIPTION_MARKER}",
        )
        val owner = repo.owner?.login ?: error("Repository has no owner")
        val branch = repo.default_branch.ifBlank { "main" }

        onStep(Step.UPLOADING_CODE)
        val rendered = templateEngine.render(spec)
        gitRepository.pushFiles(
            owner = owner,
            repo = repo.name,
            branch = branch,
            files = rendered.files,
            deletions = emptyList(), // a fresh repo has nothing to delete
            message = "Initial commit from APK Builder",
        )

        finish(owner, repo, onStep)
    }

    /**
     * Creates a project from an uploaded zip: pushes the extracted files
     * (already filtered of build outputs/caches by the importer) and makes
     * sure a build workflow exists.
     *
     * @param useOwnWorkflow true → push the zip's files untouched (it already
     *   contains .github/workflows/build.yml, and the user chose to keep it);
     *   false → drop any uploaded build.yml and add APK Builder's own.
     */
    suspend fun createProjectFromZip(
        spec: ProjectSpec,
        files: List<TemplateRenderer.RenderedFile>,
        useOwnWorkflow: Boolean,
        onStep: (Step) -> Unit = {},
    ): GithubRepo = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "No files to upload" }

        onStep(Step.CREATING_REPO)
        val appName = spec.appName.ifBlank { "Uploaded project" }
        val repoName = TemplateRenderer.repoNameFromAppName(
            if (spec.appName.isNotBlank()) spec.appName else "android-project"
        )
        val repo = projectsRepository.createProjectRepo(
            baseName = repoName,
            description = "$appName — ${ProjectsRepository.DESCRIPTION_MARKER}",
        )
        val owner = repo.owner?.login ?: error("Repository has no owner")
        val branch = repo.default_branch.ifBlank { "main" }

        onStep(Step.UPLOADING_CODE)
        val pushFiles = if (useOwnWorkflow) {
            files
        } else {
            files.filterNot { it.path == ".github/workflows/build.yml" } +
                templateEngine.workflowFile()
        }
        gitRepository.pushFiles(
            owner = owner,
            repo = repo.name,
            branch = branch,
            files = pushFiles,
            deletions = emptyList(),
            message = "Initial commit from APK Builder (uploaded project)",
        )

        finish(owner, repo, onStep)
    }

    private suspend fun finish(
        owner: String,
        repo: GithubRepo,
        onStep: (Step) -> Unit,
    ): GithubRepo {
        onStep(Step.FINISHING)
        try {
            projectsRepository.markAsApkBuilderRepo(owner, repo.name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The description already carries the fallback marker, so the
            // project stays visible on the home screen even without the topic.
        }
        return repo
    }
}
