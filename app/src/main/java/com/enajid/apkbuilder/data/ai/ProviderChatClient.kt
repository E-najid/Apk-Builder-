package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * per model profile; the OkHttp client is shared. Every call is recorded in
 * [AiDebugLog] (the 🐞 pane shows it).
 */
class ProviderChatClient(
    private val baseUrl: String,
    private val apiKey: String,
) : ChatApi {

    constructor(profile: ModelProfile) : this(profile.baseUrl, profile.apiKey)

    init {
        val trimmed = baseUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            AiDebugLog.error("http", "Base URL অবৈধ: \"${trimmed.take(40)}\"")
            throw AiException(
                "Base URL ঠিক নয় — http:// বা https:// দিয়ে শুরু হতে হবে " +
                    "(পেয়েছি: \"${trimmed.take(40)}\")"
            )
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // keep "type":"function" etc. in tool specs
    }

    override suspend fun chat(request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        AiDebugLog.info(
            "http",
            "→ POST /chat/completions · model=${request.model} · " +
                "messages=${request.messages.size} · tools=${request.tools?.size ?: 0} · " +
                "key=${AiDebugLog.redact(apiKey)}",
            details = json.encodeToString(ChatRequest.serializer(), request).take(1500),
        )
        val startedAt = System.currentTimeMillis()

        val httpRequest = Request.Builder()
            .url(url)
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
            AiDebugLog.error("http", "✗ network error after ${System.currentTimeMillis() - startedAt}ms: $url", e)
            throw AiException(
                "provider-এ পৌঁছানো যাচ্ছে না ($baseUrl) — internet ও base URL ঠিক আছে কিনা দেখো।"
            )
        }
        response.use {
            val elapsed = System.currentTimeMillis() - startedAt
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                AiDebugLog.warn(
                    "http",
                    "← HTTP ${it.code} in ${elapsed}ms ($url)",
                    details = text.take(800),
                )
                throw AiException(friendlyHttp(it.code, text))
            }
            val decoded = runCatching { json.decodeFromString(ChatResponse.serializer(), text) }
                .getOrElse {
                    AiDebugLog.error("http", "← HTTP 200 কিন্তু JSON বোঝা যায়নি", it)
                    throw AiException("provider-এর উত্তর বোঝা যায়নি (Unexpected response)।")
                }
            val message = decoded.choices.firstOrNull()?.message
            val toolNames = message?.tool_calls.orEmpty().joinToString(", ") { c -> c.function.name }
            val summary = if (toolNames.isNotEmpty()) {
                "tool_calls: $toolNames"
            } else {
                "text ${message?.content?.length ?: 0} chars"
            }
            AiDebugLog.ok(
                "http",
                "← HTTP 200 in ${elapsed}ms · $summary",
                details = message?.content?.take(400),
            )
            decoded
        }
    }

    /** Model ids for the picker in the setup dialog. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/models"
        AiDebugLog.info("http", "→ GET /models · key=${AiDebugLog.redact(apiKey)} · $url")
        val startedAt = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            AiDebugLog.error("http", "✗ network error: $url", e)
            throw AiException("provider-এ পৌঁছানো যাচ্ছে না — internet ও base URL দেখো।")
        }
        response.use {
            val elapsed = System.currentTimeMillis() - startedAt
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                AiDebugLog.warn(
                    "http",
                    "← HTTP ${it.code} in ${elapsed}ms ($url)",
                    details = text.take(800),
                )
                throw AiException(friendlyHttp(it.code, text))
            }
            val models = runCatching { json.decodeFromString(ModelsResponse.serializer(), text) }
                .getOrNull()
                ?.data
                ?.map { m -> m.id }
                ?.filter { m -> m.isNotBlank() }
                .orEmpty()
            AiDebugLog.ok(
                "http",
                "← HTTP 200 in ${elapsed}ms · ${models.size} models",
                details = models.take(15).joinToString("\n"),
            )
            models
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
        val base = when {
            code == 401 || code == 403 ->
                "API key মেনে নেওয়া হয়নি — provider-এর dashboard থেকে আবার copy করো।"
            code == 402 ->
                "Provider-এ ক্রেডিট নেই (402) — \":free\" model বেছে নাও বা ক্রেডিট যোগ করো।"
            code == 404 ->
                "Endpoint পাওয়া যায়নি — base URL দেখো ($baseUrl)।"
            code == 429 || hint.contains("rate limit") || hint.contains("quota") ->
                "Rate limit/quota শেষ (HTTP 429) — একটু পরে আবার চেষ্টা করো, বা ⚙ এ fallback model যোগ করো।"
            hint.contains("model") && (hint.contains("not") || hint.contains("invalid") || hint.contains("no endpoints")) ->
                "Model id চেনা যায়নি — \"Models লোড করো\" থেকে ঠিক id বেছে নাও।"
            else -> "provider error (HTTP $code)।"
        }
        return if (!message.isNullOrBlank() && !base.contains(message)) {
            "$base\n— provider বলছে: $message"
        } else {
            base
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
