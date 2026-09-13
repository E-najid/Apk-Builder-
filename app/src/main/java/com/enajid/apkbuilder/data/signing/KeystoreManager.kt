package com.enajid.apkbuilder.data.signing

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enajid.apkbuilder.data.TemplateRenderer
import com.enajid.apkbuilder.data.ai.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val Context.keystoreDataStore by preferencesDataStore(name = "keystore_store")

@Serializable
data class KeystoreEntry(
    val name: String,
    val fileName: String,
    val alias: String,
    val storePasswordEnc: String,
    val keyPasswordEnc: String,
    val createdAt: Long = 0L,
    val imported: Boolean = false,
)

/**
 * Manages release-signing keystores: created on-device (self-signed PKCS#12)
 * or imported from a file, stored in app-private storage with passwords
 * encrypted via Android Keystore. The active keystore is pushed to every app
 * repo (signing/ directory) so its GitHub Actions workflow can sign release
 * APKs automatically.
 */
class KeystoreManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "keystores").apply { mkdirs() }

    val entriesFlow: Flow<List<KeystoreEntry>> = context.keystoreDataStore.data.map { prefs ->
        decode(prefs[KEY_ENTRIES])
    }

    val activeFlow: Flow<String?> = context.keystoreDataStore.data.map { prefs -> prefs[KEY_ACTIVE] }

    suspend fun entries(): List<KeystoreEntry> = entriesFlow.first()

    suspend fun active(): KeystoreEntry? {
        val activeName = activeFlow.first() ?: return null
        return entries().firstOrNull { it.name == activeName }
    }

    /** Generates a fresh self-signed keystore on the device. */
    suspend fun create(
        name: String,
        alias: String,
        storePassword: String,
        keyPassword: String,
        commonName: String,
        organization: String,
    ): KeystoreEntry {
        val bytes = SelfSignedKeystore.generate(alias, storePassword, keyPassword, commonName, organization)
        return save(name, alias, bytes, storePassword, keyPassword, imported = false)
    }

    /** Imports an existing .p12/.jks produced elsewhere (keytool, Android Studio…). */
    suspend fun import(
        name: String,
        bytes: ByteArray,
        storePassword: String,
        keyPassword: String,
        aliasHint: String,
    ): KeystoreEntry {
        val alias = SelfSignedKeystore.validate(bytes, storePassword, aliasHint)
        return save(name, alias, bytes, storePassword, keyPassword, imported = true)
    }

    suspend fun setActive(name: String) {
        require(entries().any { it.name == name }) { "keystore পাওয়া যায়নি: $name" }
        context.keystoreDataStore.edit { it[KEY_ACTIVE] = name }
    }

    suspend fun delete(name: String) {
        val list = entries()
        list.firstOrNull { it.name == name }?.let { File(dir, it.fileName).delete() }
        saveEntries(list.filterNot { it.name == name })
        if (activeFlow.first() == name) {
            context.keystoreDataStore.edit { it.remove(KEY_ACTIVE) }
        }
    }

    /**
     * The files BuildOrchestrator adds to a repo so the workflow can sign:
     * `signing/release.keystore.b64` + `signing/signing.properties`.
     *
     * NOTE: these land in the (public) app repo — that's the only way to sign
     * inside GitHub Actions without secrets APIs this OAuth token can't use.
     * The UI tells users this before they enable it.
     */
    suspend fun signingFiles(): List<TemplateRenderer.RenderedFile>? {
        val entry = active() ?: return null
        val bytes = File(dir, entry.fileName).takeIf { it.exists() }?.readBytes() ?: return null
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val properties = buildString {
            appendLine("storePassword=${SecretCipher.decrypt(entry.storePasswordEnc)}")
            appendLine("keyPassword=${SecretCipher.decrypt(entry.keyPasswordEnc)}")
            appendLine("keyAlias=${entry.alias}")
        }
        return listOf(
            TemplateRenderer.RenderedFile(
                path = "signing/release.keystore.b64",
                content = b64.toByteArray(Charsets.US_ASCII),
            ),
            TemplateRenderer.RenderedFile(
                path = "signing/signing.properties",
                content = properties.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private suspend fun save(
        name: String,
        alias: String,
        bytes: ByteArray,
        storePassword: String,
        keyPassword: String,
        imported: Boolean,
    ): KeystoreEntry {
        val list = entries()
        require(name.isNotBlank()) { "নাম দাও" }
        require(list.none { it.name == name }) { "এই নামে already একটা keystore আছে" }
        val fileName = "ks-${System.currentTimeMillis()}.p12"
        File(dir, fileName).writeBytes(bytes)
        val entry = KeystoreEntry(
            name = name.trim(),
            fileName = fileName,
            alias = alias,
            storePasswordEnc = SecretCipher.encrypt(storePassword),
            keyPasswordEnc = SecretCipher.encrypt(keyPassword),
            createdAt = System.currentTimeMillis(),
            imported = imported,
        )
        val next = list + entry
        saveEntries(next)
        // First keystore becomes active automatically.
        if (list.isEmpty()) setActive(entry.name)
        return entry
    }

    private suspend fun saveEntries(list: List<KeystoreEntry>) {
        context.keystoreDataStore.edit { prefs ->
            prefs[KEY_ENTRIES] = json.encodeToString(ListSerializer(KeystoreEntry.serializer()), list)
        }
    }

    private fun decode(raw: String?): List<KeystoreEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(KeystoreEntry.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        private val KEY_ENTRIES = stringPreferencesKey("keystores_json")
        private val KEY_ACTIVE = stringPreferencesKey("active_keystore")
    }
}
