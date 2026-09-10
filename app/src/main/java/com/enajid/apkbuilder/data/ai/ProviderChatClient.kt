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

class AiException(message: String) : Exception(message)

/**
 * Generic OpenAI-compatible client: works with OpenRouter, Groq, Google
 * Gemini (OpenAI endpoint), Cerebras and any custom base URL. One instance
 * per model profile; the OkHttp client is shared.
 */
class ProviderChatClient(
    private val baseUrl: String,
    private val apiKey: String,
) : ChatApi {

    constructor(profile: ModelProfile) : this(profile.baseUrl, profile.apiKey)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // keep "type":"function" etc. in tool specs
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "APK Builder")
            .post(
                json.encodeToString(ChatRequest.serializer(), request)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        val response = try {
            client.newCall(httpRequest).await()
        } catch (e: IOException) {
            throw AiException(
                "provider-এ পৌঁছানো যাচ্ছে না ($baseUrl) — internet ও base URL ঠিক আছে কিনা দেখো।"
            )
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw AiException(friendlyHttp(it.code, text))
            return runCatching { json.decodeFromString(ChatResponse.serializer(), text) }
                .getOrElse { throw AiException("provider-এর উত্তর বোঝা যায়নি (Unexpected response).") }
        }
    }

    /** Model ids for the picker in the setup dialog. */
    suspend fun listModels(): List<String> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw AiException("provider-এ পৌঁছানো যাচ্ছে না — internet ও base URL দেখো।")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw AiException(friendlyHttp(it.code, text))
            val models = runCatching { json.decodeFromString(ModelsResponse.serializer(), text) }
                .getOrNull()
            return models?.data?.map { it.id }?.filter { it.isNotBlank() }.orEmpty()
        }
    }

    private fun friendlyHttp(code: Int, body: String): String {
        val message = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            val error = obj?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        val hint = message?.lowercase() ?: ""
        return when {
            code == 401 || code == 403 ->
                "API key মেনে নেওয়া হয়নি — provider-এর dashboard থেকে আবার copy করো।"
            code == 404 ->
                "Endpoint পাওয়া যায়নি — base URL দেখো ($baseUrl)।"
            code == 429 || hint.contains("rate limit") || hint.contains("quota") ->
                "এই model-এর ফ্রি limit/quota শেষ — একটু পরে চেষ্টা করো বা অন্য model দাও।"
            hint.contains("model") && hint.contains("not") ->
                "Model id চেনা যায়নি — \"Models লোড করো\" থেকে ঠিক id বেছে নাও।"
            else -> message?.takeIf { it.isNotBlank() } ?: "provider error (HTTP $code)।"
        }
    }

    companion object {
        // LLM answers can take a while; long read timeout on purpose.
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
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
