package com.enajid.apkbuilder.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDebugLogTest {

    @Test
    fun `redact never exposes the full key`() {
        val key = "sk-or-v1-abcdefghijklmnop"
        val redacted = AiDebugLog.redact(key)
        assertTrue(redacted, !redacted.contains(key))
        assertTrue(redacted.startsWith("sk-or"))
        assertTrue(redacted.endsWith("mnop"))
        assertEquals("(খালি)", AiDebugLog.redact(""))
    }

    @Test
    fun `ring buffer keeps only the newest entries`() {
        AiDebugLog.clear()
        repeat(AiDebugLog.MAX_ENTRIES + 50) { i ->
            AiDebugLog.info("test", "entry $i")
        }
        val snapshot = AiDebugLog.snapshot()
        assertEquals(AiDebugLog.MAX_ENTRIES, snapshot.size)
        // oldest kept entry is the (50+1)-th, newest is the last logged
        assertTrue(snapshot.first().message, snapshot.first().message.contains("entry 50"))
        assertTrue(snapshot.last().message, snapshot.last().message.contains("entry ${AiDebugLog.MAX_ENTRIES + 49}"))
        AiDebugLog.clear()
        assertTrue(AiDebugLog.snapshot().isEmpty())
    }

    @Test
    fun `share text contains the formatted entries`() {
        AiDebugLog.clear()
        AiDebugLog.ok("http", "← HTTP 200 in 42ms")
        AiDebugLog.error("agent", "boom", throwable = RuntimeException("kaboom"))
        val shared = AiDebugLog.shareText()
        assertTrue(shared, shared.contains("OK/http"))
        assertTrue(shared, shared.contains("HTTP 200"))
        assertTrue(shared, shared.contains("ERROR/agent"))
        assertTrue(shared, shared.contains("kaboom"))
        AiDebugLog.clear()
    }
}
