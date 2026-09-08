package com.enajid.apkbuilder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIdsTest {

    @Test
    fun `normalizes pasted client ids by trimming stray whitespace`() {
        assertEquals("Iv1.8a61c9f4c5f94366", ClientIds.normalize("  Iv1.8a61c9f4c5f94366 \n"))
        assertEquals("Iv1.8a61c9f4c5f94366", ClientIds.normalize("Iv1.8a61c9f4c5f94366\r\n"))
    }

    @Test
    fun `rejects junk input`() {
        assertNull(ClientIds.normalize(""))
        assertNull(ClientIds.normalize("   "))
        assertNull(ClientIds.normalize("short"))
        assertNull(ClientIds.normalize("has spaces inside that are clearly wrong"))
        assertNull(ClientIds.normalize("contains/slashes"))
        assertNull(ClientIds.normalize("REPLACE_WITH_YOUR_OAUTH_APP_CLIENT_ID"))
        assertNull(ClientIds.normalize("a".repeat(101)))
    }

    @Test
    fun `accepts real-world client id shapes`() {
        assertEquals("Iv1.8a61c9f4c5f94366", ClientIds.normalize("Iv1.8a61c9f4c5f94366"))
        // Legacy 32-char hex client IDs.
        assertEquals("0123456789abcdef0123456789abcdef", ClientIds.normalize("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `usability check catches the build-time placeholder`() {
        assertFalse(ClientIds.isUsable(null))
        assertFalse(ClientIds.isUsable(""))
        assertFalse(ClientIds.isUsable("   "))
        assertFalse(ClientIds.isUsable("REPLACE_WITH_YOUR_OAUTH_APP_CLIENT_ID"))
        assertTrue(ClientIds.isUsable("Iv1.8a61c9f4c5f94366"))
    }
}
