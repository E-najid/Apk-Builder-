package com.enajid.apkbuilder.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Users & repositories -------------------------------------------------

@Serializable
data class GithubUser(
    val login: String = "",
    val name: String? = null,
    val avatar_url: String? = null,
    val html_url: String = "",
)

@Serializable
data class GithubRepo(
    val id: Long = 0,
    val name: String = "",
    val full_name: String = "",
    val description: String? = null,
    val private: Boolean = false,
    val default_branch: String = "main",
    val html_url: String = "",
    val updated_at: String = "",
    val pushed_at: String? = null,
    val topics: List<String> = emptyList(),
    val owner: GithubUser? = null,
)

@Serializable
data class CreateRepoInput(
    val name: String,
    val description: String? = null,
    val private: Boolean = false,
    val auto_init: Boolean = false,
)

@Serializable
data class TopicsInput(val names: List<String>)

// ---- Git Data API (blobs / trees / commits / refs) --------------------------

@Serializable
data class GitRef(
    val ref: String = "",
    @SerialName("object") val obj: GitObject? = null,
)

@Serializable
data class GitObject(
    val sha: String = "",
    val type: String? = null,
)

@Serializable
data class BlobInput(
    val content: String,
    val encoding: String,
)

@Serializable
data class BlobInfo(val sha: String = "")

@Serializable
data class TreeEntryInput(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String? = null,
)

@Serializable
data class TreeInput(
    val base_tree: String? = null,
    val tree: List<TreeEntryInput>,
)

@Serializable
data class TreeEntryOut(
    val path: String = "",
    val mode: String = "",
    val type: String = "",
    val sha: String = "",
    val size: Long? = null,
)

@Serializable
data class TreeInfo(
    val sha: String = "",
    val tree: List<TreeEntryOut> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class TreeRef(val sha: String = "")

@Serializable
data class CommitInput(
    val message: String,
    val tree: String,
    val parents: List<String> = emptyList(),
)

@Serializable
data class GithubCommit(
    val sha: String = "",
    val tree: TreeRef? = null,
)

@Serializable
data class CreateRefInput(
    val ref: String,
    val sha: String,
)

@Serializable
data class UpdateRefInput(
    val sha: String,
    val force: Boolean = false,
)

// ---- Contents API -----------------------------------------------------------

@Serializable
data class ContentFile(
    val name: String = "",
    val path: String = "",
    val sha: String = "",
    val content: String? = null,
    val encoding: String? = null,
)

// ---- Actions API ------------------------------------------------------------

@Serializable
data class WorkflowRun(
    val id: Long = 0,
    val name: String = "",
    val display_title: String? = null,
    val status: String = "",
    val conclusion: String? = null,
    val head_sha: String = "",
    val event: String = "",
    val html_url: String = "",
    val run_number: Int = 0,
    val created_at: String = "",
    val updated_at: String = "",
)

@Serializable
data class WorkflowRunsResponse(
    val total_count: Int = 0,
    val workflow_runs: List<WorkflowRun> = emptyList(),
)

@Serializable
data class StepInfo(
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    val number: Int = 0,
    val started_at: String? = null,
    val completed_at: String? = null,
)

@Serializable
data class JobInfo(
    val id: Long = 0,
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    val started_at: String? = null,
    val completed_at: String? = null,
    val steps: List<StepInfo> = emptyList(),
)

@Serializable
data class JobsResponse(
    val total_count: Int = 0,
    val jobs: List<JobInfo> = emptyList(),
)

@Serializable
data class Artifact(
    val id: Long = 0,
    val name: String = "",
    val size_in_bytes: Long = 0,
    val expired: Boolean = false,
    val created_at: String = "",
)

@Serializable
data class ArtifactsResponse(
    val total_count: Int = 0,
    val artifacts: List<Artifact> = emptyList(),
)

@Serializable
data class DispatchInput(val ref: String)
