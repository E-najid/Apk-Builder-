package com.enajid.apkbuilder.data

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The subset of the GitHub REST API that APK Builder needs.
 * Called directly from the app — there is no backend server.
 */
interface GitHubApi {

    // ---- Users & repositories ----

    @GET("user")
    suspend fun currentUser(): GithubUser

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): GithubRepo

    @POST("user/repos")
    suspend fun createRepo(@Body body: CreateRepoInput): GithubRepo

    @GET("user/repos")
    suspend fun listRepos(
        @Query("type") type: String = "owner",
        @Query("sort") sort: String = "updated",
        @Query("direction") direction: String = "desc",
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1,
    ): List<GithubRepo>

    @PUT("repos/{owner}/{repo}/topics")
    suspend fun setTopics(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: TopicsInput,
    )

    // ---- Git Data API: used to push many files in a single commit ----

    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchHead(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
    ): GitRef

    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: BlobInput,
    ): BlobInfo

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: TreeInput,
    ): TreeInfo

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CommitInput,
    ): GithubCommit

    @GET("repos/{owner}/{repo}/git/commits/{sha}")
    suspend fun getCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("sha") sha: String,
    ): GithubCommit

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateRefInput,
    ): GitRef

    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body body: UpdateRefInput,
    ): GitRef

    @GET("repos/{owner}/{repo}/git/trees/{tree}")
    suspend fun getTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tree") tree: String,
        @Query("recursive") recursive: String? = "1",
    ): TreeInfo

    // ---- Contents API: used to read individual files ----

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Query("ref") ref: String,
    ): ContentFile

    // ---- Actions API ----

    @GET("repos/{owner}/{repo}/actions/runs")
    suspend fun listRuns(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("head_sha") headSha: String? = null,
        @Query("per_page") perPage: Int = 10,
    ): WorkflowRunsResponse

    @GET("repos/{owner}/{repo}/actions/runs/{runId}")
    suspend fun getRun(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): WorkflowRun

    @GET("repos/{owner}/{repo}/actions/runs/{runId}/jobs")
    suspend fun getJobs(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): JobsResponse

    @GET("repos/{owner}/{repo}/actions/runs/{runId}/artifacts")
    suspend fun getArtifacts(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): ArtifactsResponse

    @POST("repos/{owner}/{repo}/actions/workflows/{workflowId}/dispatches")
    suspend fun dispatchWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflowId") workflowId: String,
        @Body body: DispatchInput,
    )

    @GET("repos/{owner}/{repo}/actions/jobs/{jobId}/logs")
    suspend fun getJobLogs(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("jobId") jobId: Long,
    ): ResponseBody

    @GET("repos/{owner}/{repo}/actions/artifacts/{artifactId}/zip")
    suspend fun downloadArtifact(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("artifactId") artifactId: Long,
    ): ResponseBody
}
