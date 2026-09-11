package com.enajid.apkbuilder.data

import android.util.Base64
import retrofit2.HttpException

/**
 * Thin wrapper around the Git Data API. The interesting bit is
 * [pushFiles]: it creates blobs for every file, builds one tree on top of the
 * current branch, commits it and moves the ref — i.e. a multi-file push in a
 * single commit, exactly like git would do.
 *
 * GitHub's Git Data API refuses to operate on a repository with ZERO commits
 * (409 "Git Repository is empty"), so [pushFiles] bootstraps such repos with
 * a single Contents-API commit first (see [initializeEmptyRepo]).
 */
class GitRepository(private val api: GitHubApi) {

    /**
     * Pushes [files] (and applies [deletions]) in one commit on [branch].
     * Returns the new head SHA. Works on completely empty repositories too —
     * they are initialized with a placeholder commit first, which the real
     * commit then builds on top of.
     */
    suspend fun pushFiles(
        owner: String,
        repo: String,
        branch: String,
        files: List<TemplateRenderer.RenderedFile>,
        deletions: List<String> = emptyList(),
        message: String,
    ): String {
        require(files.isNotEmpty() || deletions.isNotEmpty()) { "Nothing to commit" }

        var headSha = headShaOf(owner, repo, branch)
        if (headSha == null) {
            // Zero commits: the Git Data API can't touch this repo yet.
            initializeEmptyRepo(owner, repo, branch)
            headSha = headShaOf(owner, repo, branch)
                ?: error("Could not initialize the '$branch' branch on GitHub")
        }

        val baseCommit = api.getCommit(owner, repo, headSha)
        val baseTreeSha = baseCommit.tree?.sha ?: error("Could not read the branch tree")

        // Only delete paths that actually exist in the base tree — GitHub
        // rejects null-sha entries for paths that aren't in it.
        val existingPaths = api.getTree(owner, repo, baseTreeSha).tree
            .filter { it.type == "blob" }
            .map { it.path }
            .toSet()
        val effectiveDeletions = deletions.filter { it in existingPaths }

        val entries = files.map { file ->
            val blob = api.createBlob(
                owner, repo,
                BlobInput(
                    content = Base64.encodeToString(file.content, Base64.NO_WRAP),
                    encoding = "base64",
                )
            )
            TreeEntryInput(
                path = file.path,
                mode = if (file.executable) "100755" else "100644",
                type = "blob",
                sha = blob.sha,
            )
        } + effectiveDeletions.map { path ->
            // A null sha in a tree with a base_tree deletes the file.
            TreeEntryInput(path = path, mode = "100644", type = "blob", sha = null)
        }

        val tree = api.createTree(
            owner, repo,
            TreeInput(base_tree = baseTreeSha, tree = entries)
        )
        val commit = api.createCommit(
            owner, repo,
            CommitInput(message = message, tree = tree.sha, parents = listOfNotNull(headSha))
        )
        api.updateRef(owner, repo, branch, UpdateRefInput(sha = commit.sha, force = false))
        return commit.sha
    }

    /**
     * Creates a commit with no changes. GitHub still fires the push event, so
     * this is how we trigger a build when the user taps "Build" without any
     * unsaved edits.
     */
    suspend fun createEmptyCommit(owner: String, repo: String, branch: String, message: String): String {
        val headSha = headShaOf(owner, repo, branch)
            ?: error("Branch '$branch' does not exist yet — open the editor and save first.")
        val parent = api.getCommit(owner, repo, headSha)
        val treeSha = parent.tree?.sha ?: error("Could not read the branch tree")
        val commit = api.createCommit(
            owner, repo,
            CommitInput(message = message, tree = treeSha, parents = listOf(headSha))
        )
        api.updateRef(owner, repo, branch, UpdateRefInput(sha = commit.sha, force = false))
        return commit.sha
    }

    /** Lists all file paths on [branch] (recursive). Empty list on an empty repo. */
    suspend fun listFiles(owner: String, repo: String, branch: String): List<TreeEntryOut> {
        val headSha = headShaOf(owner, repo, branch) ?: return emptyList()
        val treeSha = api.getCommit(owner, repo, headSha).tree?.sha ?: return emptyList()
        return api.getTree(owner, repo, treeSha).tree.filter { it.type == "blob" }
    }

    /** Reads a single file's raw bytes. */
    suspend fun readFile(owner: String, repo: String, branch: String, path: String): ByteArray {
        val contentFile = api.getContent(owner, repo, path, ref = branch)
        val base64 = contentFile.content
            ?.replace("\n", "")
            ?.replace("\r", "")
            ?: return ByteArray(0)
        return if (contentFile.encoding == "base64") {
            Base64.decode(base64, Base64.DEFAULT)
        } else {
            base64.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Deletes a single file via the Contents API (GitHub creates the commit).
     * [sha] must be the file's current blob sha, e.g. from the git tree.
     */
    suspend fun deleteFile(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        sha: String,
    ) {
        api.deleteContent(
            owner, repo, path,
            ContentDeleteInput(
                message = "Delete ${path.substringAfterLast('/')} (from APK Builder)",
                sha = sha,
                branch = branch,
            ),
        )
    }

    /**
     * Gives a zero-commit repository its first commit via the Contents API
     * (which, unlike the Git Data API, is allowed to do that). The placeholder
     * README is overwritten by the real template push that follows.
     */
    /**
     * Latest workflow run for the repo, formatted for the agent's
     * get_build_status tool: run number, name, status/conclusion and — when
     * it failed — the failing steps. Null when the repo has no runs yet.
     */
    suspend fun latestBuildSummary(owner: String, repo: String): String? {
        val run = api.listRuns(owner, repo, perPage = 1).workflow_runs.firstOrNull()
            ?: return null
        val state = if (run.status == "completed") {
            "completed: ${run.conclusion ?: "unknown"}"
        } else {
            run.status
        }
        return buildString {
            appendLine("run #${run.run_number} \"${run.name}\" — $state")
            appendLine("trigger: ${run.event}, updated ${run.updated_at}")
            appendLine(run.html_url)
            if (run.status == "completed" && run.conclusion == "failure") {
                val failedSteps = runCatching {
                    api.getJobs(owner, repo, run.id).jobs
                        .flatMap { job ->
                            job.steps
                                .filter { it.conclusion == "failure" }
                                .map { "${job.name} → ${it.name}" }
                        }
                }.getOrDefault(emptyList())
                if (failedSteps.isNotEmpty()) {
                    appendLine("failing steps:")
                    failedSteps.forEach { appendLine("- $it") }
                }
            }
        }.trim()
    }

    /** Recent commit subjects as "shortsha message", newest first. */
    suspend fun commitSubjects(
        owner: String,
        repo: String,
        branch: String,
        limit: Int = 10,
    ): List<String> = runCatching {
        api.listCommits(owner, repo, sha = branch, perPage = limit.coerceIn(1, 30))
            .map { "${it.sha.take(7)} ${it.commit.message.substringBefore('\n').take(100)}" }
    }.getOrDefault(emptyList())

    private suspend fun initializeEmptyRepo(owner: String, repo: String, branch: String) {
        try {
            api.putContent(
                owner, repo, "README.md",
                ContentUpdateInput(
                    message = "Initialize repository",
                    content = Base64.encodeToString(
                        "# Project\n\nSet up with APK Builder.\n".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    ),
                    branch = branch,
                )
            )
        } catch (e: HttpException) {
            // 409/422 here means the repo already got its first commit (e.g. a
            // concurrent attempt) — nothing to initialize, carry on.
            if (e.code() != 409 && e.code() != 422) throw e
        }
    }

    private suspend fun headShaOf(owner: String, repo: String, branch: String): String? =
        try {
            api.getBranchHead(owner, repo, branch).obj?.sha
        } catch (e: HttpException) {
            // 409 = empty repository (no commits yet), 404 = ref doesn't exist.
            if (e.code() == 404 || e.code() == 409) null else throw e
        }
}
