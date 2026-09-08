package com.enajid.apkbuilder.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

@Serializable
private data class GithubErrorMessage(val message: String? = null)

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Turns exceptions from the GitHub API into short, human-readable messages.
 */
fun Throwable.friendlyMessage(): String = when (this) {
    is CancellationException -> message ?: "Cancelled"
    is HttpException -> {
        val raw = try {
            response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        val parsed = raw?.let {
            runCatching { errorJson.decodeFromString(GithubErrorMessage.serializer(), it) }.getOrNull()
        }
        when (code()) {
            401 -> "GitHub says your sign-in expired. Please sign out and sign in again."
            403 -> parsed?.message?.takeIf { it.isNotBlank() }
                ?: "GitHub refused the request (this can happen on rate limits). Wait a moment and try again."
            404 -> "Not found on GitHub — the repository may have been renamed or deleted."
            422 -> parsed?.message?.takeIf { it.isNotBlank() } ?: "GitHub rejected the request."
            else -> parsed?.message?.takeIf { it.isNotBlank() } ?: "GitHub error (HTTP ${code()})."
        }
    }
    is IOException -> "Network trouble — check your internet connection and try again."
    else -> message ?: "Something went wrong."
}
