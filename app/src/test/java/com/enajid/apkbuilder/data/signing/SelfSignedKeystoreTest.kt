package com.enajid.apkbuilder.data.signing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore

class SelfSignedKeystoreTest {

    @Test
    fun `generated keystore loads back with the same credentials`() {
        val bytes = SelfSignedKeystore.generate(
            alias = "release",
            storePassword = "store-pass-123",
            keyPassword = "key-pass-123",
            commonName = "Test Dev",
            organization = "Test Org",
        )

        assertTrue(bytes.size > 500)

        val store = KeyStore.getInstance("PKCS12")
        store.load(bytes.inputStream(), "store-pass-123".toCharArray())
        assertTrue(store.isKeyEntry("release"))
        val chain = store.getCertificateChain("release")
        assertEquals(1, chain.size.toLong())
        assertEquals("CN=Test Dev, O=Test Org", (chain[0] as java.security.cert.X509Certificate).subjectX500Principal.name)
    }

    @Test
    fun `validate returns the alias and auto-detects when hint is blank`() {
        val bytes = SelfSignedKeystore.generate(
            alias = "mykey",
            storePassword = "store-pass-123",
            keyPassword = "key-pass-123",
            commonName = "",
            organization = "",
        )
        assertEquals("explicit", SelfSignedKeystore.validate(bytes, "store-pass-123", "explicit"))
        assertEquals("mykey", SelfSignedKeystore.validate(bytes, "store-pass-123", ""))
    }

    @Test
    fun `wrong store password is rejected`() {
        val bytes = SelfSignedKeystore.generate(
            alias = "a", storePassword = "store-pass-123", keyPassword = "k",
            commonName = "", organization = "",
        )
        try {
            SelfSignedKeystore.validate(bytes, "wrong", "")
            throw AssertionError("expected failure")
        } catch (expected: Exception) {
            // any exception type is fine (password integrity check)
        }
    }

    @Test
    fun `short store password is rejected up front`() {
        try {
            SelfSignedKeystore.generate("a", "123", "k", "", "")
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("৬"))
        }
    }
}
