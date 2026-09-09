package com.enajid.apkbuilder.data

import retrofit2.HttpException

/**
 * Repository-ish operations around GitHub repos: creating them, listing the
 * ones that belong to APK Builder (marked with the `apk-builder` topic).
 */
class ProjectsRepository(private val api: GitHubApi) {

    /**
     * Lists the user's own repositories and returns the ones created by
     * APK Builder. Primary marker: the `apk-builder` topic. Fallback marker:
     * the description suffix we set at creation (in case setting the topic
     * ever fails — the project must not become invisible).
     */
    suspend fun listProjects(): List<GithubRepo> {
        val all = mutableListOf<GithubRepo>()
        var page = 1
        while (page <= MAX_PAGES) {
            val batch = api.listRepos(page = page)
            all += batch
            if (batch.size < PAGE_SIZE) break
            page++
        }
        return all
            .filter { repo ->
                repo.topics.contains(MARKER_TOPIC) ||
                    repo.description?.contains(DESCRIPTION_MARKER) == true
            }
            .sortedByDescending { (it.pushed_at ?: it.updated_at).orEmpty() }
    }

    /**
     * Creates a fresh public repo, born with an initial commit
     * (auto-init README) so the Git Data API can operate on it immediately.
     *
     * If the name is already taken on the account, checks whether it's a
     * leftover from a previous failed attempt (public + essentially empty) —
     * if so, reuses it instead of piling up "name-2", "name-3"… duplicates.
     * Only then falls back to a suffixed name.
     */
    suspend fun createProjectRepo(baseName: String, description: String?): GithubRepo {
        val login = api.currentUser().login
        var candidate = baseName
        var lastError: HttpException? = null
        for (attempt in 0 until NAME_ATTEMPTS) {
            try {
                return api.createRepo(
                    CreateRepoInput(
                        name = candidate,
                        description = description,
                        private = false,
                        auto_init = true,
                    )
                )
            } catch (e: HttpException) {
                lastError = e
                if (e.code() != 422) throw e
                val existing = runCatching { api.getRepo(login, candidate) }.getOrNull()
                if (existing != null && isLeftoverAttempt(existing)) return existing
                candidate = "$baseName-${attempt + 2}"
            }
        }
        throw lastError ?: IllegalStateException("Could not create the repository")
    }

    suspend fun markAsApkBuilderRepo(owner: String, repo: String) {
        api.setTopics(owner, repo, TopicsInput(listOf(MARKER_TOPIC)))
    }

    /**
     * True when [repo] is almost certainly a leftover from a previous,
     * failed create attempt: public, and containing nothing but the
     * auto-init README (or no commits at all). Adopting it is safe; a real
     * user repo with the same name never matches, so we rename instead of
     * touching it.
     */
    private suspend fun isLeftoverAttempt(repo: GithubRepo): Boolean {
        if (repo.private) return false
        val owner = repo.owner?.login ?: return false
        val branch = repo.default_branch.ifBlank { "main" }
        val blobs = try {
            api.getTree(owner, repo.name, branch).tree
                .filter { it.type == "blob" }
                .map { it.path }
        } catch (e: HttpException) {
            // 409 = zero commits ("Git Repository is empty"), 404 = no such
            // ref — both mean "no real content", i.e. safe to adopt.
            return e.code() == 409 || e.code() == 404
        }
        return blobs.all { it == "README.md" }
    }

    companion object {
        const val MARKER_TOPIC = "apk-builder"
        const val DESCRIPTION_MARKER = "built with APK Builder"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 5
        private const val NAME_ATTEMPTS = 5
    }
}
