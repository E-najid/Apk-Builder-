package com.enajid.apkbuilder.domain

/**
 * Folders and files that are never pushed when importing an uploaded zip.
 * Build outputs, caches and VCS internals would bloat the repo (or break the
 * 500-file/size limits), and keystores must never land in a public repo.
 */
object UploadFilter {

    private val EXCLUDED_DIRS = setOf(
        ".git", ".gradle", ".idea", ".kotlin", ".cxx", ".dart_tool", ".expo",
        ".externalNativeBuild", "build", "captures", "node_modules", "Pods",
    )

    private val EXCLUDED_FILES = setOf(
        ".DS_Store", "local.properties",
    )

    fun isExcluded(relPath: String): Boolean {
        val segments = relPath.split('/')
        if (segments.dropLast(1).any { it in EXCLUDED_DIRS }) return true
        val name = segments.lastOrNull() ?: return true
        if (name in EXCLUDED_FILES) return true
        if (name.endsWith(".iml")) return true
        // Never push signing material to a public repository.
        if (name.endsWith(".jks") || name.endsWith(".keystore")) return true
        return false
    }
}
