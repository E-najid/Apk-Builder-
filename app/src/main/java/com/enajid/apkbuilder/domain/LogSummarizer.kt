package com.enajid.apkbuilder.domain

/**
 * Turns a (potentially huge) GitHub Actions job log into a few lines that
 * explain, in plain terms, why a build failed. No raw log dumps.
 */
object LogSummarizer {

    private val ANSI_CODES = Regex("\u001B\\[[0-9;]*m")

    private val LINE_PATTERNS = listOf(
        Regex("^e: .+"),                            // kotlinc errors
        Regex(".*error:.*", RegexOption.IGNORE_CASE),
        Regex("^FAILURE: .*"),
        Regex(".*Execution failed for task.*"),
        Regex(".*Unresolved reference.*"),
        Regex(".*Caused by:.*"),
        Regex(".*Could not (?:find|resolve|get).*"),
    )

    fun summarize(log: String, maxLines: Int = 6): List<String> =
        ANSI_CODES.replace(log, "")
            .lineSequence()
            .filter { line -> LINE_PATTERNS.any { it.matches(line) } }
            .map { line -> line.trim().take(200) }
            .distinct()
            .take(maxLines)
            .toList()
}
