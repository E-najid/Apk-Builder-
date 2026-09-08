package com.enajid.apkbuilder.data

import android.util.Base64
import retrofit2.HttpException

/**
 * Thin wrapper around the Git Data API. The interesting bit is
 * [pushFiles]: it creates blobs for every file, builds one tree on top of the
 * current branch, commits it and moves the ref — i.e. a multi-file push in a
 * single commit, exactly like git would do.
 */
class GitRepository(private val api: GitHubApi) {

    /**
     * Pushes [files] (and applies [deletions]) in one commit on [branch].
     * Returns the new head SHA. Works on empty repositories too (creates the
     * branch with its first commit).
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

        val headSha = headShaOf(owner, repo, branch)

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
        } + deletions.map { path ->
            // A null sha in a tree with a base_tree deletes the file.
            TreeEntryInput(path = path, mode = "100644", type = "blob", sha = null)
        }

        val baseTreeSha = headSha?.let { api.getCommit(owner, repo, it).tree?.sha }
        val tree = api.createTree(
            owner, repo,
            TreeInput(base_tree = baseTreeSha, tree = entries)
        )
        val commit = api.createCommit(
            owner, repo,
            CommitInput(message = message, tree = tree.sha, parents = listOfNotNull(headSha))
        )
        if (headSha == null) {
            api.createRef(owner, repo, CreateRefInput(ref = "refs/heads/$branch", sha = commit.sha))
        } else {
            api.updateRef(owner, repo, branch, UpdateRefInput(sha = commit.sha, force = false))
        }
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

    /** Lists all file paths on [branch] (recursive). */
    suspend fun listFiles(owner: String, repo: String, branch: String): List<TreeEntryOut> =
        api.getTree(owner, repo, branch).tree.filter { it.type == "blob" }

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

    private suspend fun headShaOf(owner: String, repo: String, branch: String): String? =
        try {
            api.getBranchHead(owner, repo, branch).obj?.sha
        } catch (e: HttpException) {
            // 409 = empty repository (no commits yet), 404 = ref doesn't exist.
            if (e.code() == 404 || e.code() == 409) null else throw e
        }
}
