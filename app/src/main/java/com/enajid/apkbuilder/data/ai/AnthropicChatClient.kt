package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

/**
 * Native Anthropic (Claude) Messages API client — the same [ChatApi]
 * surface as [ProviderChatClient], so Claude profiles can even sit in the
 * same fallback chain as OpenAI-compatible models. Wire mapping lives in
 * [AnthropicWire]; HTTP + SSE here.
 */
class AnthropicChatClient(
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

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/messages"
        val body = AnthropicWire.buildBody(request).toString()
        AiDebugLog.info(
            "http",
            "→ POST /messages (anthropic) · model=${request.model} · " +
                "messages=${request.messages.size} · tools=${request.tools?.size ?: 0} · " +
                "key=${AiDebugLog.redact(apiKey)}",
            details = body.take(1500),
        )
        val startedAt = System.currentTimeMillis()
        val response = try {
            client.newCall(buildRequest(url, body)).await()
        } catch (e: java.net.SocketTimeoutException) {
            throw AiException("provider অনেক সময় নিচ্ছে (timeout) — আবার চেষ্টা করো বা অন্য model দাও।")
        } catch (e: IOException) {
            AiDebugLog.error("http", "✗ network error: $url", e)
            throw AiException("provider-এ পৌঁছানো যাচ্ছে না ($baseUrl) — internet ও base URL দেখো।")
        }
        response.use {
            val elapsed = System.currentTimeMillis() - startedAt
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                AiDebugLog.warn("http", "← HTTP ${it.code} in ${elapsed}ms ($url)", details = text.take(800))
                throw AiException(friendlyHttp(it.code, text))
            }
            val decoded = runCatching { AnthropicWire.parseResponse(text) }
                .getOrElse {
                    AiDebugLog.error("http", "← HTTP 200 কিন্তু JSON বোঝা যায়নি", it)
                    throw AiException("provider-এর উত্তর বোঝা যায়নি (Unexpected response)।")
                }
            val message = decoded.choices.firstOrNull()?.message
            AiDebugLog.ok(
                "http",
                "← HTTP 200 in ${elapsed}ms · " +
                    if (message?.tool_calls.isNullOrEmpty()) {
                        "text ${message?.content?.length ?: 0} chars"
                    } else {
                        "tool_calls: ${message?.tool_calls!!.joinToString(", ") { c -> c.function.name }}"
                    },
                details = message?.content?.take(400),
            )
            requireContent(decoded, request.model)
        }
    }

    /**
     * SSE streaming: Anthropic sends typed events (message_start,
     * content_block_delta …); text deltas are forwarded live and the final
     * ChatResponse is assembled from the accumulated blocks.
     */
    override suspend fun chatStream(request: ChatRequest, onDelta: (String) -> Unit): ChatResponse =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/messages"
            val body = AnthropicWire.buildBody(request.copy(stream = true)).toString()
            AiDebugLog.info("http", "→ POST /messages (anthropic, stream) · model=${request.model}")
            val startedAt = System.currentTimeMillis()
            val response = try {
                client.newCall(buildRequest(url, body)).await()
            } catch (e: IOException) {
                throw AiException("provider-এ পৌঁছানো যাচ্ছে না ($baseUrl) — internet ও base URL দেখো।")
            }
            response.use {
                if (!it.isSuccessful) {
                    val text = it.body?.string().orEmpty()
                    AiDebugLog.warn("http", "← HTTP ${it.code} in ${System.currentTimeMillis() - startedAt}ms", details = text.take(600))
                    throw AiException(friendlyHttp(it.code, text))
                }
                val accumulator = AnthropicWire.StreamAccumulator(onDelta)
                it.body?.charStream()?.buffered()?.useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (!line.startsWith("data:")) continue
                        accumulator.onPayload(line.removePrefix("data:").trim())
                    }
                }
                AiDebugLog.ok("http", "← stream done in ${System.currentTimeMillis() - startedAt}ms (anthropic)")
                requireContent(accumulator.build(), request.model)
            }
        }

    /** Model ids for the picker in the setup dialog. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/models"
        AiDebugLog.info("http", "→ GET /models (anthropic) · key=${AiDebugLog.redact(apiKey)}")
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", AnthropicWire.API_VERSION)
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw AiException("provider-এ পৌঁছানো যাচ্ছে না — internet ও base URL দেখো।")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                AiDebugLog.warn("http", "← HTTP ${it.code} ($url)", details = text.take(800))
                throw AiException(friendlyHttp(it.code, text))
            }
            runCatching { json.decodeFromString(ModelsResponse.serializer(), text) }
                .getOrNull()
                ?.data
                ?.map { m -> m.id }
                .orEmpty()
        }
    }

    private fun buildRequest(url: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", AnthropicWire.API_VERSION)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    /**
     * Same rule as ProviderChatClient: a blank answer (no text AND no tool
     * calls) is a provider failure so the fallback chain can switch models.
     */
    private fun requireContent(response: ChatResponse, model: String): ChatResponse {
        val message = response.choices.firstOrNull()?.message
        val empty = message == null ||
            (message.content.isNullOrBlank() && message.tool_calls.isNullOrEmpty())
        if (empty) {
            AiDebugLog.warn("http", "← HTTP 200 কিন্তু উত্তর খালি — model: $model")
            throw AiException("খালি উত্তর দিলো: ${model.take(60)} (tool calling সাপোর্ট করে না)")
        }
        return response
    }

    private fun friendlyHttp(code: Int, body: String): String {
        val message = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            (obj?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        val hint = message?.lowercase() ?: ""
        val base = when {
            code == 401 || code == 403 ->
                "API key মেনে নেওয়া হয়নি — Anthropic console থেকে আবার copy করো।"
            code == 404 -> "Endpoint পাওয়া যায়নি — base URL দেখো ($baseUrl)।"
            code == 429 || hint.contains("rate") || hint.contains("quota") ->
                "Rate limit/quota শেষ (HTTP 429) — একটু পরে আবার চেষ্টা করো।"
            hint.contains("credit") -> "Anthropic ক্রেডিট নেই — billing দেখো।"
            else -> "provider error (HTTP $code)।"
        }
        return if (!message.isNullOrBlank() && !base.contains(message)) {
            "$base\n— provider বলছে: $message"
        } else {
            base
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
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
