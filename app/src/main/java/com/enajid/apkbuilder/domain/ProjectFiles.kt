package com.enajid.apkbuilder.domain

/**
 * Project files the cloud build cannot work without. The editor refuses to
 * delete these — a project missing any of them will not compile on GitHub
 * Actions, and the failure would be confusing for a beginner.
 */
object ProjectFiles {

    private val CRITICAL_ROOT_FILES = setOf(
        "settings.gradle",
        "settings.gradle.kts",
        "build.gradle",
        "build.gradle.kts",
        "app/build.gradle",
        "app/build.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        ".github/workflows/build.yml",
    )

    fun isCriticalToBuild(path: String): Boolean = when {
        path.startsWith("gradle/wrapper/") -> true
        path in CRITICAL_ROOT_FILES -> true
        path.endsWith("src/main/AndroidManifest.xml") -> true
        else -> false
    }

    /** Human-readable explanation shown when deletion is blocked. */
    fun criticalReason(path: String): String? =
        if (isCriticalToBuild(path)) {
            "“$path” is required for the cloud build — the project wouldn’t " +
                "compile on GitHub without it. You can still edit its contents."
        } else {
            null
        }
}
