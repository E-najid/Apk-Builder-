package com.enajid.apkbuilder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSummarizerTest {

    @Test
    fun `extracts the meaningful error lines`() {
        val log = """
            2024-05-01T10:00:00.1234567Z Current runner version: '2.316.1'
            2024-05-01T10:00:01Z ##[group]Run ./gradlew assembleDebug
            2024-05-01T10:00:02Z ##[endgroup]
            e: file:///home/runner/work/app/app/app/src/main/java/com/example/MainActivity.kt:12:5 Unresolved reference: frobnicate
            > Task :app:compileDebugKotlin FAILED
            FAILURE: Build failed with an exception.
            * What went wrong:
            Execution failed for task ':app:compileDebugKotlin'.
            * Try again with --stacktrace for details.
        """.trimIndent()

        val summary = LogSummarizer.summarize(log)

        assertTrue(summary.any { it.contains("Unresolved reference: frobnicate") })
        assertTrue(summary.any { it.contains("Execution failed for task") })
        assertTrue(summary.size <= 6)
        assertTrue(summary.none { it.contains("runner version") })
    }

    @Test
    fun `no matches returns empty list`() {
        val summary = LogSummarizer.summarize("nothing to see here\njust noise\n")
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `very long lines are truncated`() {
        val longLine = "error: " + "x".repeat(500)
        val summary = LogSummarizer.summarize(longLine)
        assertEquals(1, summary.size)
        assertTrue(summary[0].length <= 200)
    }
}
