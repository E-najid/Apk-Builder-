package com.enajid.apkbuilder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enajid.apkbuilder.data.ai.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "apk_builder_settings")

/**
 * Stores the GitHub OAuth token (and login name) locally on the device.
 * The token never leaves the device except in requests to GitHub itself.
 *
 * At rest the token is sealed with SecretCipher (hardware-backed Android
 * Keystore AES/GCM) via [TokenCrypto]; plaintext exists only in the
 * in-memory cache. Tokens saved by older versions are migrated on the next
 * app start — reads keep working either way because SecretCipher.decrypt
 * passes legacy plaintext values through.
 *
 * Also stores the optional OAuth client ID entered in the app's one-time
 * setup screen — still no server, no database, just app-private storage.
 */
class TokenStore(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var cached: String? = null

    /** "" when signed out. `null` until DataStore has been read for the first time. */
    val tokenFlow: Flow<String> = context.dataStore.data.map { prefs ->
        TokenCrypto.forMemory(prefs[TOKEN_KEY].orEmpty(), SecretCipher::decrypt)
    }

    val loginFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[LOGIN_KEY] }

    /** Client ID saved from the setup screen; overrides the build-time one. */
    val clientIdOverrideFlow: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[CLIENT_ID_KEY] }

    val cachedToken: String?
        get() = synchronized(lock) { cached }

    fun updateCached(value: String?) {
        synchronized(lock) { cached = value?.takeIf { it.isNotBlank() } }
    }

    suspend fun save(token: String, login: String) {
        updateCached(token) // in-memory cache stays plaintext for HTTP calls
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = TokenCrypto.forStorage(token, SecretCipher::encrypt)
            prefs[LOGIN_KEY] = login
        }
    }

    /**
     * One-time migration: tokens written by older versions sit in DataStore
     * as plaintext. Re-saves just the token, sealed. Called once at app
     * start; harmless when there is nothing to migrate.
     */
    suspend fun migrateLegacyToken() {
        val prefs = context.dataStore.data.first()
        val stored = prefs[TOKEN_KEY].orEmpty()
        if (TokenCrypto.needsMigration(stored, SecretCipher::isEncrypted)) {
            context.dataStore.edit { it[TOKEN_KEY] = TokenCrypto.forStorage(stored, SecretCipher::encrypt) }
        }
    }

    suspend fun clear() {
        updateCached(null)
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(LOGIN_KEY)
        }
    }

    suspend fun saveClientIdOverride(clientId: String) {
        context.dataStore.edit { prefs -> prefs[CLIENT_ID_KEY] = clientId }
    }

    suspend fun clearClientIdOverride() {
        context.dataStore.edit { prefs -> prefs.remove(CLIENT_ID_KEY) }
    }

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("github_token")
        private val LOGIN_KEY = stringPreferencesKey("github_login")
        private val CLIENT_ID_KEY = stringPreferencesKey("oauth_client_id_override")
    }
}
