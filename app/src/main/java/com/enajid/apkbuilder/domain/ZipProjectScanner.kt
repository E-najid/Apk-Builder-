package com.enajid.apkbuilder.domain

/**
 * Scans the files of an uploaded zip and detects everything the
 * "Create project" form needs. Pure Kotlin — fully unit-tested, no Android.
 *
 * Nothing from the zip is ever executed here; this only reads text bytes.
 * The code runs exclusively inside GitHub's own sandboxed Actions runner.
 */
data class ZipScanResult(
    val valid: Boolean,
    val invalidReason: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val framework: Framework? = null,
    val iconBytes: ByteArray? = null,
    val isGradleProject: Boolean = false,
    val hasOwnWorkflow: Boolean = false,
)

object ZipProjectScanner {

    fun scan(rawFiles: Map<String, ByteArray>): ZipScanResult {
        val files = stripCommonRoot(rawFiles)
        if (files.isEmpty()) return invalid("The zip is empty.")

        // Plausibility: something Android-ish must be present, otherwise we'd
        // show a confusing empty form.
        val manifests = files.keys.filter { it.endsWith("AndroidManifest.xml") }
        val hasPubspec = files.keys.any { it.endsWith("pubspec.yaml") }
        val hasPackageJson = files.keys.any { it.endsWith("package.json") }
        if (manifests.isEmpty() && !hasPubspec && !hasPackageJson) {
            return invalid(
                "Couldn’t find an Android project in this zip — no AndroidManifest.xml, " +
                    "pubspec.yaml or package.json. Is it a zip of the project’s source folder?"
            )
        }

        val hasOwnWorkflow = files.keys.any { it == ".github/workflows/build.yml" }

        val isGradleProject = files.keys.any { it == "settings.gradle" || it == "settings.gradle.kts" } &&
            files.keys.any { it == "build.gradle" || it == "build.gradle.kts" }

        // Framework: config files first, then majority vote on extensions.
        val framework = when {
            hasPubspec -> Framework.FLUTTER
            hasPackageJson && files.entries
                .filter { it.key == "package.json" }
                .any { String(it.value).contains("react-native") } -> Framework.REACT_NATIVE
            else -> {
                val kt = files.keys.count { it.endsWith(".kt") }
                val java = files.keys.count { it.endsWith(".java") }
                when {
                    kt > java -> Framework.KOTLIN
                    java > 0 -> Framework.JAVA
                    else -> null
                }
            }
        }

        // The app module's build script is where the interesting values live.
        val buildFile = listOf(
            "app/build.gradle.kts", "app/build.gradle",
            "android/app/build.gradle.kts", "android/app/build.gradle",
        ).firstOrNull { files.containsKey(it) }
        val buildText = buildFile?.let { String(files.getValue(it)) }

        val manifestFile = listOf(
            "app/src/main/AndroidManifest.xml",
            "android/app/src/main/AndroidManifest.xml",
            "AndroidManifest.xml",
        ).firstOrNull { files.containsKey(it) }
            ?: manifests.firstOrNull()
        val manifestText = manifestFile?.let { String(files.getValue(it)) }

        val packageName = firstMatch(
            buildText,
            listOf("""(?:namespace|applicationId)\s*[=:]?\s*["']([A-Za-z0-9_.]+)["']"""),
        ) ?: firstMatch(
            manifestText,
            listOf("""package\s*=\s*"([A-Za-z0-9_.]+)"""),
        )

        val minSdk = intProp(buildText, listOf("minSdk", "minSdkVersion"))
            ?: intProp(manifestText, listOf("minSdkVersion"))
        val targetSdk = intProp(buildText, listOf("targetSdk", "targetSdkVersion"))
            ?: intProp(manifestText, listOf("targetSdkVersion"))

        val appName = detectAppName(files) ?: firstMatch(
            manifestText,
            listOf("""android:label="([^"@]+)"""),
        )?.trim()?.takeIf { it.isNotBlank() }

        return ZipScanResult(
            valid = true,
            appName = appName,
            packageName = packageName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            framework = framework,
            iconBytes = detectIcon(files),
            isGradleProject = isGradleProject,
            hasOwnWorkflow = hasOwnWorkflow,
        )
    }

    /** Reads `<string name="app_name">…</string>` from res/values/strings.xml. */
    private fun detectAppName(files: Map<String, ByteArray>): String? {
        val candidates = files.keys
            .filter { Regex("""(^|/)res/values/strings\.xml$""").containsMatchIn(it) }
            .sortedBy { it.length }
        for (path in candidates) {
            val text = String(files.getValue(path))
            val m = Regex("""<string\s+name="app_name"[^>]*>(.*?)</string>""").find(text) ?: continue
            val value = m.groupValues[1]
                .replace("\\'", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** Picks the best launcher icon: highest density first, png over webp. */
    private fun detectIcon(files: Map<String, ByteArray>): ByteArray? {
        val iconPattern = Regex("""(^|/)res/mipmap-[^/]+/ic_launcher\.(png|webp)$""")
        val densityOrder = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")
        val candidates = files.keys.filter { iconPattern.containsMatchIn(it) }
        val ranked = candidates.sortedWith(
            compareByDescending<String> { path ->
                densityOrder.indexOfFirst { path.contains("mipmap-$it/") }
                    .let { if (it < 0) -1 else densityOrder.size - it }
            }.thenByDescending { it.endsWith(".png") }
        )
        for (path in ranked) {
            val bytes = files.getValue(path)
            if (bytes.size in 1..MAX_ICON_BYTES) return bytes
        }
        return null
    }

    private fun intProp(text: String?, names: List<String>): Int? {
        if (text == null) return null
        for (name in names) {
            val m = Regex("""$name\s*[=:]?\s*["']?(\d+)""").find(text) ?: continue
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun firstMatch(text: String?, patterns: List<String>): String? {
        if (text == null) return null
        for (pattern in patterns) {
            Regex(pattern).find(text)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * Zips made by file managers often wrap everything in one folder
     * ("MyProject/…"). If every path shares the same first segment and no
     * file sits at the root, strip that wrapper.
     */
    private fun stripCommonRoot(files: Map<String, ByteArray>): Map<String, ByteArray> {
        if (files.size < 2) return files
        val firstSegments = files.keys.map { it.substringBefore('/') }.toSet()
        if (firstSegments.size != 1) return files
        val wrapper = firstSegments.first()
        if (files.keys.any { !it.contains('/') }) return files
        return files.mapKeys { it.key.removePrefix("$wrapper/") }
    }

    private fun invalid(reason: String) = ZipScanResult(valid = false, invalidReason = reason)

    private const val MAX_ICON_BYTES = 2 * 1024 * 1024
}
