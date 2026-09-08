package com.enajid.apkbuilder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "apk_builder_settings")

/**
 * Stores the GitHub OAuth token (and login name) locally on the device.
 * The token never leaves the device except in requests to GitHub itself.
 */
class TokenStore(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var cached: String? = null

    /** "" when signed out. `null` until DataStore has been read for the first time. */
    val tokenFlow: Flow<String> = context.dataStore.data.map { prefs -> prefs[TOKEN_KEY].orEmpty() }

    val loginFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[LOGIN_KEY] }

    val cachedToken: String?
        get() = synchronized(lock) { cached }

    fun updateCached(value: String?) {
        synchronized(lock) { cached = value?.takeIf { it.isNotBlank() } }
    }

    suspend fun save(token: String, login: String) {
        updateCached(token)
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
            prefs[LOGIN_KEY] = login
        }
    }

    suspend fun clear() {
        updateCached(null)
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(LOGIN_KEY)
        }
    }

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("github_token")
        private val LOGIN_KEY = stringPreferencesKey("github_login")
    }
}
