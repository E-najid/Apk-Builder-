package com.enajid.apkbuilder.data

/**
 * Pure mapping between the stored and in-memory GitHub OAuth token.
 *
 * At rest the token is sealed with [com.enajid.apkbuilder.data.ai.SecretCipher]
 * (a hardware-backed Android Keystore key) before it lands in DataStore; in
 * memory — and only in memory — it is plaintext so the GitHub HTTP client
 * can use it.
 *
 * The cipher itself needs Android, so it is injected as a function — that
 * keeps this mapper unit-testable on the JVM. Same shape as the AI
 * profiles' ProfileCrypto.
 */
object TokenCrypto {

    /** Seals the token right before persisting (blank stays blank). */
    fun forStorage(token: String, encrypt: (String) -> String): String =
        if (token.isBlank()) token else encrypt(token)

    /** Unseals the token right after reading (blank stays blank). */
    fun forMemory(stored: String, decrypt: (String) -> String): String =
        if (stored.isBlank()) stored else decrypt(stored)

    /**
     * True when the stored token is still plaintext — i.e. it was written
     * by an older version and should be re-saved sealed.
     */
    fun needsMigration(stored: String, isEncrypted: (String) -> Boolean): Boolean =
        stored.isNotBlank() && !isEncrypted(stored)
}
