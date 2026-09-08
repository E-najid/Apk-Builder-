package com.enajid.apkbuilder.data

import retrofit2.HttpException

/**
 * Repository-ish operations around GitHub repos: creating them, listing the
 * ones that belong to APK Builder (marked with the `apk-builder` topic).
 */
class ProjectsRepository(private val api: GitHubApi) {

    suspend fun getRepo(owner: String, repo: String): GithubRepo = api.getRepo(owner, repo)

    /**
     * Lists the user's own repositories and returns the ones created by
     * APK Builder (identified by the `apk-builder` topic).
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
            .filter { it.topics.contains(MARKER_TOPIC) }
            .sortedByDescending { (it.pushed_at ?: it.updated_at).orEmpty() }
    }

    /**
     * Creates a fresh public repo. If the name is already taken on the account,
     * retries with "-2", "-3", … suffixes before giving up.
     */
    suspend fun createProjectRepo(baseName: String, description: String?): GithubRepo {
        var candidate = baseName
        var lastError: HttpException? = null
        for (attempt in 0 until NAME_ATTEMPTS) {
            try {
                return api.createRepo(
                    CreateRepoInput(
                        name = candidate,
                        description = description,
                        private = false,
                        auto_init = false,
                    )
                )
            } catch (e: HttpException) {
                lastError = e
                if (e.code() == 422 && attempt < NAME_ATTEMPTS - 1) {
                    candidate = "$baseName-${attempt + 2}"
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("Could not create the repository")
    }

    suspend fun markAsApkBuilderRepo(owner: String, repo: String) {
        api.setTopics(owner, repo, TopicsInput(listOf(MARKER_TOPIC)))
    }

    companion object {
        const val MARKER_TOPIC = "apk-builder"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 5
        private const val NAME_ATTEMPTS = 5
    }
}
