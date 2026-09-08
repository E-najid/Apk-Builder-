package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.Framework
import com.enajid.apkbuilder.domain.ProjectSpec

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
    ): GithubRepo {
        check(spec.framework == Framework.KOTLIN) { "Only Kotlin projects are supported in v1" }

        onStep(Step.CREATING_REPO)
        val repoName = TemplateRenderer.repoNameFromAppName(spec.appName)
        val repo = projectsRepository.createProjectRepo(
            baseName = repoName,
            description = "${spec.appName} — built with APK Builder",
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
            deletions = rendered.deletions,
            message = "Initial commit from APK Builder",
        )

        onStep(Step.FINISHING)
        projectsRepository.markAsApkBuilderRepo(owner, repo.name)
        return repo
    }
}
