package com.enajid.apkbuilder.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local (on-device) cache of unsaved editor changes per project, so nothing is
 * lost if the app is killed mid-edit. Cleared once changes are committed to
 * GitHub.
 */
class LocalProjectStore(private val context: Context) {

    @Serializable
    private data class Store(val files: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(owner: String, repo: String): File =
        File(File(context.filesDir, "projects"), "${owner}__${repo}.json")

    fun loadDirty(owner: String, repo: String): Map<String, String> = runCatching {
        val file = fileFor(owner, repo)
        if (!file.exists()) {
            emptyMap()
        } else {
            json.decodeFromString(Store.serializer(), file.readText()).files
        }
    }.getOrDefault(emptyMap())

    fun markDirty(owner: String, repo: String, path: String, content: String) {
        val files = loadDirty(owner, repo).toMutableMap()
        files[path] = content
        val file = fileFor(owner, repo)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Store.serializer(), Store(files)))
    }

    /** Removes a single path from the unsaved-edits cache (e.g. file deleted). */
    fun removeDirtyFile(owner: String, repo: String, path: String) {
        val files = loadDirty(owner, repo).toMutableMap()
        if (files.remove(path) == null) return
        val file = fileFor(owner, repo)
        if (files.isEmpty()) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Store.serializer(), Store(files)))
        }
    }

    fun clearDirty(owner: String, repo: String) {
        fileFor(owner, repo).delete()
    }
}
