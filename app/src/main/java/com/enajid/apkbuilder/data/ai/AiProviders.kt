package com.enajid.apkbuilder.data.ai

import kotlinx.serialization.Serializable

/**
 * A built-in provider preset. Everything speaks the OpenAI-compatible chat
 * format, so any preset (and any custom URL) works with the same client.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** Where the user creates an API key (opened in the browser). */
    val keyUrl: String? = null,
    val freeHint: String? = null,
)

object ProviderPresets {
    val OPENROUTER = ProviderPreset(
        id = "openrouter",
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        keyUrl = "https://openrouter.ai/settings/keys",
        freeHint = "এক key-তে ৩০০+ model — নামে \":free\" থাকলে ফ্রি",
    )
    val GROQ = ProviderPreset(
        id = "groq",
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        keyUrl = "https://console.groq.com/keys",
        freeHint = "ফ্রি tier, খুব দ্রুত",
    )
    val GEMINI = ProviderPreset(
        id = "gemini",
        label = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        keyUrl = "https://aistudio.google.com/apikey",
        freeHint = "ফ্রি tier, generous limit",
    )
    val CEREBRAS = ProviderPreset(
        id = "cerebras",
        label = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        keyUrl = "https://cloud.cerebras.ai/",
        freeHint = "ফ্রি tier, দ্রুত",
    )
    val CUSTOM = ProviderPreset(
        id = "custom",
        label = "Custom",
        baseUrl = "",
        keyUrl = null,
        freeHint = "যেকোনো OpenAI-compatible URL",
    )

    val all = listOf(OPENROUTER, GROQ, GEMINI, CEREBRAS, CUSTOM)

    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id } ?: CUSTOM
}

/** What a configured model is used for. */
@Serializable
enum class ModelRole {
    /** Writes the code (the main agent). */
    CODER,

    /** Reviews the coder's changes once and reports issues. */
    REVIEWER,

    /** Backup — used automatically when an earlier model fails. */
    FALLBACK,
}

/** One configured AI model: provider + credentials + model id + its job. */
@Serializable
data class ModelProfile(
    val id: Long,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val role: ModelRole = ModelRole.FALLBACK,
    val enabled: Boolean = true,
) {
    val providerLabel: String get() = ProviderPresets.byId(providerId).label

    /** Short line for lists, e.g. "OpenRouter · deepseek/deepseek-chat-v3.1:free". */
    val summary: String get() = "$providerLabel · $model"
}
