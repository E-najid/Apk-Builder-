package com.enajid.apkbuilder.data

import android.content.Context
import android.os.SystemClock
import com.enajid.apkbuilder.data.ai.AiProfilesStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tiny hand-rolled dependency container. Everything talks to GitHub directly —
 * there is no backend server of our own anywhere in this app.
 */
class AppContainer(context: Context) {

    val tokenStore = TokenStore(context)
    val localProjectStore = LocalProjectStore(context)
    val templateEngine = TemplateEngine(context)

    // The AI agent's user-configured model profiles + skills, stored as JSON
    // in app-private DataStore (no database anywhere).
    val aiProfilesStore = AiProfilesStore(context)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor())
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
            tokenStore.cachedToken?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }
        .build()

    val gitHubApi: GitHubApi = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)

    val authRepository = AuthRepository(DeviceFlowClient(), tokenStore, gitHubApi)
    val projectsRepository = ProjectsRepository(gitHubApi)
    val gitRepository = GitRepository(gitHubApi)
    val actionsRepository = ActionsRepository(gitHubApi)
    val projectCreator = ProjectCreator(projectsRepository, gitRepository, templateEngine)
    val buildOrchestrator = BuildOrchestrator(gitRepository, actionsRepository, localProjectStore)

    /**
     * Retries a request a couple of times when the connection itself fails
     * (timeouts, connection resets, DNS hiccups — common on mobile networks).
     *
     * Every GitHub endpoint this app calls is safe to re-issue: repo-creation
     * name collisions are handled by reusing the existing empty repo (see
     * ProjectsRepository), Git blobs/trees/commits are content-addressed and
     * orphaned duplicates are harmless, and topic updates are idempotent.
     */
    private class RetryInterceptor(
        private val maxAttempts: Int = 3,
        private val firstBackoffMs: Long = 1_000L,
    ) : okhttp3.Interceptor {

        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val request = chain.request()
            var lastError: IOException? = null
            for (attempt in 0 until maxAttempts) {
                try {
                    if (attempt > 0) {
                        SystemClock.sleep(firstBackoffMs * attempt)
                    }
                    return chain.proceed(request)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("GitHub request failed after $maxAttempts attempts: ${request.url}")
        }
    }
}
