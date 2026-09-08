package com.enajid.apkbuilder.domain

/**
 * Validation and normalization of GitHub OAuth client IDs.
 *
 * Client IDs are public identifiers (they ship inside every OAuth app), so
 * they can be baked in at build time, stored on-device, or typed by the user
 * in the one-time setup screen — no server, no database involved.
 */
object ClientIds {

    /** The value app/build.gradle.kts falls back to when no Gradle property is set. */
    const val PLACEHOLDER_PREFIX = "REPLACE_WITH"

    private val FORMAT = Regex("^[A-Za-z0-9._-]+$")

    /**
     * Cleans up a pasted client ID (strips whitespace/newlines) and returns it
     * if it looks plausible, otherwise null. We intentionally don't get
     * stricter than this — GitHub is the source of truth, and a wrong ID gets
     * a clear error message from the device-flow request.
     */
    fun normalize(raw: String): String? {
        val cleaned = raw.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
        if (cleaned.length !in 8..100) return null
        if (!FORMAT.matches(cleaned)) return null
        if (cleaned.startsWith(PLACEHOLDER_PREFIX)) return null
        return cleaned
    }

    /** True when an ID is present and isn't the build-time placeholder. */
    fun isUsable(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (id.startsWith(PLACEHOLDER_PREFIX)) return false
        return id.length >= 8
    }
}
