package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The single operation the agent loop needs from a chat backend. */
interface ChatApi {
    suspend fun chat(request: ChatRequest): ChatResponse
}

class OmniRouteException(message: String) : Exception(message)

enum class OmniRouteStatus { RUNNING, STOPPED }

/**
 * Minimal OpenAI-compatible client for the OmniRoute gateway the user runs
 * locally in Termux (default http://localhost:20128/v1).
 *
 * Requests never leave the device — OmniRoute runs on the same phone, and it
 * routes to whatever AI accounts/models the user connected in its dashboard.
 */
class OmniRouteClient(private val settings: AiSettingsStore) : ChatApi {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // keep "type":"function" etc. in tool specs
    }

    // LLM answers can take a while; long read timeout on purpose.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val config = settings.current()
        if (!config.hasKey) {
            throw OmniRouteException("No OmniRoute API key set — open the AI setup once.")
        }
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val body = json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()

        val response = try {
            client.newCall(httpRequest).await()
        } catch (e: IOException) {
            throw OmniRouteException(
                "Couldn't reach OmniRoute at ${config.baseUrl}. Is it running? " +
                    "Open Termux and run: omniroute"
            )
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw OmniRouteException(friendlyHttp(it.code, text))
            return runCatching { json.decodeFromString(ChatResponse.serializer(), text) }
                .getOrElse { throw OmniRouteException("Unexpected response from OmniRoute.") }
        }
    }

    /** Model ids for the picker in settings. */
    suspend fun listModels(): List<String> {
        val config = settings.current()
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw OmniRouteException("Couldn't reach OmniRoute — is it running in Termux?")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw OmniRouteException(friendlyHttp(it.code, text))
            val models = runCatching { json.decodeFromString(ModelsResponse.serializer(), text) }
                .getOrNull()
            return models?.data?.map { it.id }?.filter { it.isNotBlank() }.orEmpty()
        }
    }

    /**
     * Cheap reachability probe. Any HTTP answer — even a 401 — means the
     * gateway is up; only a connection failure means it's down.
     */
    suspend fun ping(): OmniRouteStatus {
        val config = settings.current()
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/models")
            .get()
            .build()
        return try {
            pingClient.newCall(request).await().use { OmniRouteStatus.RUNNING }
        } catch (e: Exception) {
            OmniRouteStatus.STOPPED
        }
    }

    private fun friendlyHttp(code: Int, body: String): String {
        val message = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            val error = obj?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return when (code) {
            401 -> "OmniRoute rejected the API key — copy a fresh one from its dashboard (Endpoint page)."
            404 -> "Endpoint not found — check the base URL (usually ${AiConfig.DEFAULT_BASE_URL})."
            else -> message?.takeIf { it.isNotBlank() } ?: "OmniRoute error (HTTP $code)."
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
