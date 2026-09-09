package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.Framework
import com.enajid.apkbuilder.domain.ProjectSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates a new project end-to-end: repo + customized template + marker topic.
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
        check(spec.framework == Framework.KOTLIN) { "Only Kotlin projects are supported in v1" }

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

        onStep(Step.FINISHING)
        try {
            projectsRepository.markAsApkBuilderRepo(owner, repo.name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The description already carries the fallback marker, so the
            // project stays visible on the home screen even without the topic.
        }
        repo
    }
}
