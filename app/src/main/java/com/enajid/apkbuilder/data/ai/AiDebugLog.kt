package com.enajid.apkbuilder.data.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The AI debug helper: a small in-memory ring buffer that records every AI
 * HTTP exchange (request summary, status, latency, response snippets), agent
 * steps, provider fallbacks and errors — with stack traces.
 *
 * It is intentionally a separate, dependency-free class so any layer
 * (client, agent, view model) can log without wiring. Entries are also
 * appended to `filesDir/ai_debug.log` so evidence survives a crash, and a
 * default uncaught-exception handler writes the stack of a fatal crash into
 * that file (the previous handler still runs).
 *
 * API keys are NEVER logged in full — [redact] shows only the first and last
 * few characters.
 */
object AiDebugLog {

    enum class Level { INFO, OK, WARN, ERROR, FATAL }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val event: String,
        val message: String,
        val details: String? = null,
    )

    private val lock = Any()
    private val buffer = mutableListOf<Entry>()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-debug-log").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null
    @Volatile private var attached = false

    /** Wires persistence + crash capture. Safe to call more than once. */
    fun attach(context: Context) {
        if (attached) return
        synchronized(lock) { attached = true }
        logFile = File(context.filesDir, "ai_debug.log")

        // Load the previous session's tail so crash evidence is visible.
        io.execute {
            val tail = runCatching { logFile?.readLines() }.getOrDefault(emptyList())
                .takeLast(MAX_PERSISTED_LINES)
            if (tail.isNotEmpty()) {
                log(
                    Level.WARN, "startup",
                    "আগের সেশনের শেষ ${tail.size} লাইন লগ (crash হয়ে থাকলে কারণ এখানেই):",
                    details = tail.joinToString("\n").take(4000),
                )
            }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logFile?.appendText(
                    "FATAL ${Date()} thread=${thread.name}\n" +
                        "${throwable.stackTraceToString().take(6000)}\n"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(level: Level, event: String, message: String, details: String? = null) {
        val entry = Entry(System.currentTimeMillis(), level, event, message, details)
        synchronized(lock) {
            buffer += entry
            while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
        }
        persist(entry)
    }

    fun info(event: String, message: String, details: String? = null) =
        log(Level.INFO, event, message, details)

    fun ok(event: String, message: String, details: String? = null) =
        log(Level.OK, event, message, details)

    fun warn(event: String, message: String, details: String? = null) =
        log(Level.WARN, event, message, details)

    /** Logs an error with its stack trace — never rethrows. */
    fun error(event: String, message: String, throwable: Throwable? = null) {
        val details = throwable?.stackTraceToString()?.take(3000)
        log(Level.ERROR, event, message, details)
    }

    /** A new snapshot of the entries, oldest first. */
    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        io.execute { runCatching { logFile?.writeText("") } }
    }

    /** Everything, formatted — for the copy/share button. */
    fun shareText(): String = snapshot().joinToString("\n\n") { format(it) }

    fun format(entry: Entry): String = buildString {
        append('[').append(formatTime(entry.timeMs)).append("] ")
        append(entry.level).append('/').append(entry.event).append(": ")
        append(entry.message)
        entry.details?.let { append("\n").append(it) }
    }

    fun formatTime(timeMs: Long): String =
        synchronized(timeFormat) { timeFormat.format(Date(timeMs)) }

    /** "sk-or-v1-abcdef…WXYZ" — safe to log. */
    fun redact(secret: String): String = when {
        secret.isBlank() -> "(খালি)"
        secret.length <= 8 -> "…${secret.takeLast(2)}"
        else -> "${secret.take(5)}…${secret.takeLast(4)}"
    }

    private fun persist(entry: Entry) {
        val file = logFile ?: return
        io.execute {
            runCatching {
                file.appendText(format(entry) + "\n")
                if (file.length() > MAX_FILE_BYTES) {
                    val kept = file.readLines().takeLast(MAX_PERSISTED_LINES)
                    file.writeText(kept.joinToString("\n") + "\n")
                }
            }
        }
    }

    const val MAX_ENTRIES = 400
    private const val MAX_PERSISTED_LINES = 300
    private const val MAX_FILE_BYTES = 256 * 1024
}
