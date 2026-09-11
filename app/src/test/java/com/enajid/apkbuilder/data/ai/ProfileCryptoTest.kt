package com.enajid.apkbuilder.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCryptoTest {

    private fun profile(key: String) = ModelProfile(
        id = 1L,
        providerId = "openrouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = key,
        model = "test/model",
    )

    /** Fake "cipher": marks values as sealed so tests run on the JVM. */
    private fun fakeEncrypt(s: String) = "enc:v1:fake($s)"
    private fun fakeDecrypt(s: String) = s.removePrefix("enc:v1:fake(").removeSuffix(")")
    private fun fakeIsEncrypted(s: String) = s.startsWith("enc:v1:")

    @Test
    fun `round trip keeps the api key intact`() {
        val original = listOf(profile("sk-secret-123"), profile(key = ""))

        val stored = ProfileCrypto.forStorage(original, ::fakeEncrypt)
        val back = ProfileCrypto.forMemory(stored, ::fakeDecrypt)

        assertEquals(original, back)
    }

    @Test
    fun `storage form runs every key through the cipher`() {
        val raw = "sk-secret-123"
        val stored = ProfileCrypto.forStorage(listOf(profile(raw)), ::fakeEncrypt)

        // The stored value is exactly the cipher's output, never the raw key.
        assertEquals(fakeEncrypt(raw), stored.single().apiKey)
        assertFalse(stored.single().apiKey == raw)
    }

    @Test
    fun `blank keys are left alone`() {
        val stored = ProfileCrypto.forStorage(listOf(profile(key = "")), ::fakeEncrypt)

        assertEquals("", stored.single().apiKey)
    }

    @Test
    fun `migration triggers only for legacy plaintext keys`() {
        // Legacy plaintext -> needs migration
        assertTrue(
            ProfileCrypto.hasPlaintextKeys(listOf(profile("sk-plain")), ::fakeIsEncrypted)
        )
        // Already sealed -> no migration
        assertFalse(
            ProfileCrypto.hasPlaintextKeys(
                listOf(profile(fakeEncrypt("sk-sealed"))), ::fakeIsEncrypted
            )
        )
        // Blank key -> nothing to seal
        assertFalse(
            ProfileCrypto.hasPlaintextKeys(listOf(profile(key = "")), ::fakeIsEncrypted)
        )
        // Mixed -> needs migration (one plaintext is one too many)
        assertTrue(
            ProfileCrypto.hasPlaintextKeys(
                listOf(profile(fakeEncrypt("a")), profile("b")), ::fakeIsEncrypted
            )
        )
    }

    @Test
    fun `migration flow seals legacy keys and reads them back unchanged`() {
        // What AiProfilesStore.profiles() does for a legacy list:
        val legacy = listOf(profile("sk-plain"), profile("sk-other"))
        val resealed = ProfileCrypto.forStorage(legacy, ::fakeEncrypt) // saveProfiles()
        val readable = ProfileCrypto.forMemory(resealed, ::fakeDecrypt) // profiles()

        assertFalse(ProfileCrypto.hasPlaintextKeys(resealed, ::fakeIsEncrypted))
        assertEquals(legacy, readable)
    }
}
