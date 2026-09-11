package com.enajid.apkbuilder.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Parsing/formatting for the ISO-8601 timestamps the GitHub API returns. */
object TimeUtils {

    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    )

    fun parseGitHubTime(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        for (pattern in PATTERNS) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse(iso)?.time
            } catch (_: ParseException) {
                // try the next pattern
            }
        }
        return null
    }

    fun durationBetween(startIso: String?, endIso: String?): Long? {
        val start = parseGitHubTime(startIso) ?: return null
        val end = parseGitHubTime(endIso) ?: return null
        return (end - start).coerceAtLeast(0)
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    fun relativeTime(iso: String?, nowMs: Long = System.currentTimeMillis()): String {
        val time = parseGitHubTime(iso) ?: return ""
        val diff = nowMs - time
        return when {
            diff < 60_000L -> "just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            diff < 30L * 86_400_000L -> "${diff / 86_400_000L}d ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(time))
        }
    }
}
