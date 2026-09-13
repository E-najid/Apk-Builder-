package com.enajid.apkbuilder.data

import retrofit2.HttpException

/** Operations on the GitHub Actions API: runs, jobs, steps and artifacts. */
class ActionsRepository(private val api: GitHubApi) {

    suspend fun findRunForSha(owner: String, repo: String, commitSha: String): WorkflowRun? =
        api.listRuns(owner, repo, headSha = commitSha, perPage = 5).workflow_runs.firstOrNull()

    suspend fun getRun(owner: String, repo: String, runId: Long): WorkflowRun =
        api.getRun(owner, repo, runId)

    suspend fun getJobs(owner: String, repo: String, runId: Long): List<JobInfo> =
        api.getJobs(owner, repo, runId).jobs

    suspend fun getArtifacts(owner: String, repo: String, runId: Long): List<Artifact> =
        api.getArtifacts(owner, repo, runId).artifacts

    suspend fun dispatch(owner: String, repo: String, branch: String) {
        api.dispatchWorkflow(owner, repo, "build.yml", DispatchInput(ref = branch))
    }

    /** Raw log text of a job, or null when logs are no longer available. */
    suspend fun jobLogs(owner: String, repo: String, jobId: Long): String? =
        try {
            api.getJobLogs(owner, repo, jobId).string()
        } catch (e: HttpException) {
            null
        }

    /** Downloads the artifact zip (contains the built APK). */
    suspend fun downloadArtifactZip(owner: String, repo: String, artifactId: Long): ByteArray =
        api.downloadArtifact(owner, repo, artifactId).bytes()
}
