package com.enajid.apkbuilder.data.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.aiDataStore by preferencesDataStore(name = "ai_settings")

/** A reusable instruction pack injected into the agent's system prompt. */
@Serializable
data class Skill(
    val id: Long,
    val name: String,
    val instructions: String,
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Stores the user's AI model profiles and skills as JSON in app-private
 * DataStore — no database, nothing ever leaves the device except calls to
 * the providers the user configured themselves.
 *
 * API keys are sealed at rest with [SecretCipher] (Android Keystore) via
 * [ProfileCrypto]: they exist in plaintext only in memory. Profiles saved
 * by older versions are re-encrypted transparently on the next read.
 */
class AiProfilesStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val profilesFlow: Flow<List<ModelProfile>> = context.aiDataStore.data.map { prefs ->
        ProfileCrypto.forMemory(
            decode(prefs[KEY_PROFILES], ModelProfile.serializer()) { seedProfiles() },
            SecretCipher::decrypt,
        )
    }

    val skillsFlow: Flow<List<Skill>> = context.aiDataStore.data.map { prefs ->
        decode(prefs[KEY_SKILLS], Skill.serializer()) { builtInSkills() }.ifEmpty { builtInSkills() }
    }

    suspend fun profiles(): List<ModelProfile> {
        val stored = context.aiDataStore.data.first()[KEY_PROFILES]
        val list = decode(stored, ModelProfile.serializer()) { seedProfiles() }
        if (ProfileCrypto.hasPlaintextKeys(list, SecretCipher::isEncrypted)) {
            // Written by an older version: the keys still read fine, this
            // just seals them at rest.
            saveProfiles(list)
        }
        return ProfileCrypto.forMemory(list, SecretCipher::decrypt)
    }

    suspend fun skills(): List<Skill> = skillsFlow.first()

    suspend fun addProfile(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        role: ModelRole,
    ): ModelProfile {
        val current = profiles()
        val profile = ModelProfile(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
            providerId = providerId,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
            role = role,
        )
        saveProfiles(current + profile)
        return profile
    }

    suspend fun updateProfile(profile: ModelProfile) {
        saveProfiles(profiles().map { if (it.id == profile.id) profile else it })
    }

    suspend fun deleteProfile(id: Long) {
        saveProfiles(profiles().filterNot { it.id == id })
    }

    suspend fun setProfileEnabled(id: Long, enabled: Boolean) {
        saveProfiles(profiles().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /**
     * Sets the role of one profile. Coder and reviewer are single-slot roles:
     * whoever held the role before becomes a fallback.
     */
    suspend fun setRole(id: Long, role: ModelRole) {
        saveProfiles(
            profiles().map {
                when {
                    it.id == id -> it.copy(role = role)
                    role == ModelRole.CODER && it.role == ModelRole.CODER ->
                        it.copy(role = ModelRole.FALLBACK)
                    role == ModelRole.REVIEWER && it.role == ModelRole.REVIEWER ->
                        it.copy(role = ModelRole.FALLBACK)
                    else -> it
                }
            }
        )
    }

    suspend fun toggleSkill(id: Long) {
        saveSkills(skills().map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    suspend fun addSkill(name: String, instructions: String): Skill {
        val current = skills()
        val skill = Skill(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
            name = name.trim(),
            instructions = instructions.trim(),
        )
        saveSkills(current + skill)
        return skill
    }

    suspend fun deleteSkill(id: Long) {
        saveSkills(skills().filterNot { it.id == id })
    }

    private suspend fun saveProfiles(list: List<ModelProfile>) {
        val sealed = ProfileCrypto.forStorage(list, SecretCipher::encrypt)
        context.aiDataStore.edit { prefs ->
            prefs[KEY_PROFILES] = json.encodeToString(ListSerializer(ModelProfile.serializer()), sealed)
        }
    }

    private suspend fun saveSkills(list: List<Skill>) {
        context.aiDataStore.edit { prefs ->
            prefs[KEY_SKILLS] = json.encodeToString(ListSerializer(Skill.serializer()), list)
        }
    }

    private fun <T> decode(
        raw: String?,
        serializer: kotlinx.serialization.KSerializer<T>,
        fallback: () -> List<T>,
    ): List<T> {
        if (raw.isNullOrBlank()) return fallback()
        return runCatching { json.decodeFromString(ListSerializer(serializer), raw) }
            .getOrDefault(fallback())
    }

    companion object {
        private val KEY_PROFILES = stringPreferencesKey("model_profiles_json")
        private val KEY_SKILLS = stringPreferencesKey("skills_json")

        /** First profile starts as the coder — one model is enough to begin. */
        private fun seedProfiles(): List<ModelProfile> = emptyList()

        fun builtInSkills(): List<Skill> = listOf(
            Skill(
                id = 1L,
                name = "Compose idioms",
                instructions = "Use modern Jetpack Compose correctly: hoist state, " +
                    "remember derived values, LaunchedEffect for one-shot work, " +
                    "Material3 components, no deprecated APIs.",
                builtIn = true,
            ),
            Skill(
                id = 2L,
                name = "ছোট APK",
                instructions = "Never add new third-party dependencies. Only " +
                    "androidx.core, androidx.activity, Compose UI + Material3 " +
                    "(compose BOM) are available. Keep the code minimal.",
                builtIn = true,
            ),
            Skill(
                id = 3L,
                name = "Bengali UI",
                instructions = "Any user-facing strings in the app code should be " +
                    "written in Bengali (বাংলা), unless the user explicitly asks " +
                    "for another language.",
                builtIn = true,
            ),
            Skill(
                id = 4L,
                name = "Kotlin style",
                instructions = "Follow official Kotlin conventions: val over var, " +
                    "expression bodies where clearer, meaningful names, no unused " +
                    "imports or dead code.",
                builtIn = true,
            ),
        )
    }
}
