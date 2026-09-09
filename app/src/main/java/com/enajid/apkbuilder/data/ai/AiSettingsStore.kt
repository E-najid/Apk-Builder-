package com.enajid.apkbuilder.data.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.aiDataStore by preferencesDataStore(name = "ai_settings")

data class AiConfig(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        /** OmniRoute's default local endpoint (run by the user in Termux). */
        const val DEFAULT_BASE_URL = "http://localhost:20128/v1"
        const val DEFAULT_MODEL = "auto/coding"
    }
}

/** Stores the OmniRoute connection settings on-device (no server anywhere). */
class AiSettingsStore(private val context: Context) {

    val configFlow: Flow<AiConfig> = context.aiDataStore.data.map { prefs ->
        AiConfig(
            apiKey = prefs[KEY_API].orEmpty(),
            baseUrl = prefs[KEY_BASE]?.takeIf { it.isNotBlank() } ?: AiConfig.DEFAULT_BASE_URL,
            model = prefs[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: AiConfig.DEFAULT_MODEL,
        )
    }

    suspend fun current(): AiConfig = configFlow.first()

    suspend fun save(apiKey: String, baseUrl: String, model: String) {
        context.aiDataStore.edit { prefs ->
            prefs[KEY_API] = apiKey.trim()
            prefs[KEY_BASE] = baseUrl.trim()
            prefs[KEY_MODEL] = model.trim()
        }
    }

    private companion object {
        val KEY_API = stringPreferencesKey("omniroute_api_key")
        val KEY_BASE = stringPreferencesKey("omniroute_base_url")
        val KEY_MODEL = stringPreferencesKey("omniroute_model")
    }
}
