package com.enajid.apkbuilder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCryptoTest {

    /** Fake "cipher": marks values as sealed so tests run on the JVM. */
    private fun fakeEncrypt(s: String) = "enc:v1:fake($s)"
    private fun fakeDecrypt(s: String) = s.removePrefix("enc:v1:fake(").removeSuffix(")")
    private fun fakeIsEncrypted(s: String) = s.startsWith("enc:v1:")

    @Test
    fun `round trip keeps the token intact`() {
        val token = "gho_abc123secret"

        val stored = TokenCrypto.forStorage(token, ::fakeEncrypt)
        val back = TokenCrypto.forMemory(stored, ::fakeDecrypt)

        assertEquals(token, back)
    }

    @Test
    fun `storage form is exactly the cipher output`() {
        val token = "gho_abc123secret"

        val stored = TokenCrypto.forStorage(token, ::fakeEncrypt)

        assertEquals(fakeEncrypt(token), stored)
    }

    @Test
    fun `signed-out stays signed-out`() {
        // Blank token (signed out) must never run through the cipher.
        assertEquals("", TokenCrypto.forStorage("", ::fakeEncrypt))
        assertEquals("", TokenCrypto.forMemory("", ::fakeDecrypt))
    }

    @Test
    fun `legacy plaintext token round trips through decrypt untouched`() {
        // Old versions stored plaintext; SecretCipher.decrypt passes it
        // through, so the fake does the same shape of thing here.
        assertEquals("gho_legacy", TokenCrypto.forMemory("gho_legacy") { it })
    }

    @Test
    fun `migration triggers only for legacy plaintext tokens`() {
        // Plaintext, non-blank -> migrate
        assertTrue(TokenCrypto.needsMigration("gho_plain", ::fakeIsEncrypted))
        // Already sealed -> no
        assertFalse(TokenCrypto.needsMigration(fakeEncrypt("gho_sealed"), ::fakeIsEncrypted))
        // Signed out -> nothing to migrate
        assertFalse(TokenCrypto.needsMigration("", ::fakeIsEncrypted))
    }

    @Test
    fun `migration flow seals a legacy token and reads it back unchanged`() {
        // What TokenStore.migrateLegacyToken() + tokenFlow do for legacy data:
        val legacy = "gho_legacy_plain"
        val resealed = TokenCrypto.forStorage(legacy, ::fakeEncrypt) // migrateLegacyToken()
        val readable = TokenCrypto.forMemory(resealed, ::fakeDecrypt) // tokenFlow

        assertFalse(TokenCrypto.needsMigration(resealed, ::fakeIsEncrypted))
        assertEquals(legacy, readable)
    }
}
