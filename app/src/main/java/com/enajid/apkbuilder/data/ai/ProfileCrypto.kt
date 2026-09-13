package com.enajid.apkbuilder.data.ai

/**
 * Pure mapping between stored and in-memory model profiles.
 *
 * On disk every profile's `apiKey` is sealed with [SecretCipher] (a
 * hardware-backed Android Keystore key) before it lands in DataStore; in
 * memory — and only in memory — the key is plaintext so the HTTP clients
 * can use it.
 *
 * The cipher itself needs Android, so it is injected as a function — that
 * keeps this mapper unit-testable on the JVM.
 */
object ProfileCrypto {

    /** Encrypts every non-blank api key (call right before persisting). */
    fun forStorage(
        list: List<ModelProfile>,
        encrypt: (String) -> String,
    ): List<ModelProfile> = list.map { profile ->
        if (profile.apiKey.isBlank()) profile
        else profile.copy(apiKey = encrypt(profile.apiKey))
    }

    /** Decrypts every non-blank api key (call right after reading). */
    fun forMemory(
        list: List<ModelProfile>,
        decrypt: (String) -> String,
    ): List<ModelProfile> = list.map { profile ->
        if (profile.apiKey.isBlank()) profile
        else profile.copy(apiKey = decrypt(profile.apiKey))
    }

    /**
     * True when any stored key is still plaintext — i.e. the profile list
     * was written by an older version and should be re-saved encrypted.
     */
    fun hasPlaintextKeys(
        list: List<ModelProfile>,
        isEncrypted: (String) -> Boolean,
    ): Boolean = list.any { it.apiKey.isNotBlank() && !isEncrypted(it.apiKey) }
}
