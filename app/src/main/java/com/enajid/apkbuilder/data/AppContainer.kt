package com.enajid.apkbuilder.data

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Tiny hand-rolled dependency container. Everything talks to GitHub directly —
 * there is no backend server of our own anywhere in this app.
 */
class AppContainer(context: Context) {

    val tokenStore = TokenStore(context)
    val localProjectStore = LocalProjectStore(context)
    val templateEngine = TemplateEngine(context)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
}
