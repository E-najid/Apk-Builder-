package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.Framework
import com.enajid.apkbuilder.domain.ProjectSpec

/**
 * Pure (Android-free) rendering of the Kotlin project template.
 *
 * Template files live in the APK's assets under `templates/kotlin-app/`.
 * Two asset names differ from their output names because aapt's default
 * asset-merge ignore pattern silently DROPS dot-prefixed files AND
 * underscore-prefixed directories from the APK:
 *   - `dot-github/workflows/build.yml` -> `.github/workflows/build.yml`
 *   - `dot-gitignore`                  -> `.gitignore`
 * (This is not theoretical: the underscore form shipped once and made every
 * project creation fail with a bogus "network trouble" error.)
 *
 * The renderer substitutes `{{PLACEHOLDER}}` tokens and applies the right
 * escaping per destination (Kotlin string literal vs. XML text).
 */
object TemplateRenderer {

    const val TEMPLATE_ROOT = "templates/kotlin-app"
    const val ICON_OUTPUT_PATH = "app/src/main/res/drawable/ic_launcher.xml"
    const val ICON_PNG_OUTPUT_PATH = "app/src/main/res/drawable/ic_launcher.png"

    data class RenderedFile(
        val path: String,
        val content: ByteArray,
        val executable: Boolean = false,
    ) {
        fun asText(): String = content.toString(Charsets.UTF_8)
    }

    data class RenderResult(
        val files: List<RenderedFile>,
        val deletions: List<String>,
    )

    private data class Entry(
        val asset: String,
        val output: String,
        val binary: Boolean = false,
        val executable: Boolean = false,
    )

    /** Asset root for a framework (see [render]). */
    fun templateRoot(framework: Framework): String = when (framework) {
        Framework.KOTLIN -> "templates/kotlin-app"
        Framework.JAVA -> "templates/java-app"
        else -> throw IllegalArgumentException("No template available yet for $framework")
    }

    private fun entriesFor(framework: Framework): List<Entry> = when (framework) {
        Framework.KOTLIN -> kotlinEntries
        Framework.JAVA -> javaEntries
        else -> throw IllegalArgumentException("No template available yet for $framework")
    }

    private val kotlinEntries = listOf(
        Entry("dot-github/workflows/build.yml", ".github/workflows/build.yml"),
        Entry("dot-gitignore", ".gitignore"),
        Entry("README.md", "README.md"),
        Entry("settings.gradle.kts", "settings.gradle.kts"),
        Entry("build.gradle.kts", "build.gradle.kts"),
        Entry("gradle.properties", "gradle.properties"),
        Entry("gradlew", "gradlew", executable = true),
        Entry("gradlew.bat", "gradlew.bat"),
        Entry("gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.jar", binary = true),
        Entry("gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.properties"),
        Entry("app/build.gradle.kts", "app/build.gradle.kts"),
        Entry("app/proguard-rules.pro", "app/proguard-rules.pro"),
        Entry("app/src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"),
        Entry("app/src/main/java/MainActivity.kt", "app/src/main/java/{{PACKAGE_PATH}}/MainActivity.kt"),
        Entry("app/src/main/res/drawable/ic_launcher.xml", "app/src/main/res/drawable/ic_launcher.xml"),
        Entry("app/src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml"),
        Entry("app/src/main/res/values/themes.xml", "app/src/main/res/values/themes.xml"),
    )

    private val javaEntries = listOf(
        Entry("dot-github/workflows/build.yml", ".github/workflows/build.yml"),
        Entry("dot-gitignore", ".gitignore"),
        Entry("README.md", "README.md"),
        Entry("settings.gradle.kts", "settings.gradle.kts"),
        Entry("build.gradle.kts", "build.gradle.kts"),
        Entry("gradle.properties", "gradle.properties"),
        Entry("gradlew", "gradlew", executable = true),
        Entry("gradlew.bat", "gradlew.bat"),
        Entry("gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.jar", binary = true),
        Entry("gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.properties"),
        Entry("app/build.gradle.kts", "app/build.gradle.kts"),
        Entry("app/proguard-rules.pro", "app/proguard-rules.pro"),
        Entry("app/src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"),
        Entry("app/src/main/java/MainActivity.java", "app/src/main/java/{{PACKAGE_PATH}}/MainActivity.java"),
        Entry("app/src/main/res/drawable/ic_launcher.xml", "app/src/main/res/drawable/ic_launcher.xml"),
        Entry("app/src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml"),
        Entry("app/src/main/res/values/themes.xml", "app/src/main/res/values/themes.xml"),
    )

    fun render(spec: ProjectSpec, readAsset: (String) -> ByteArray): RenderResult {
        val root = templateRoot(spec.framework)
        val entries = entriesFor(spec.framework)
        val values = mapOf(
            "APP_NAME" to spec.appName,
            "APP_NAME_KOTLIN" to escapeKotlinString(spec.appName),
            "APP_NAME_XML" to escapeXmlText(spec.appName),
            "PACKAGE_NAME" to spec.packageName,
            "PACKAGE_PATH" to spec.packageName.replace('.', '/'),
            "MIN_SDK" to spec.minSdk.toString(),
            "TARGET_SDK" to spec.targetSdk.toString(),
            "COMPILE_SDK" to spec.compileSdk.toString(),
        )
        val files = mutableListOf<RenderedFile>()
        for (entry in entries) {
            // A custom icon replaces the default vector drawable.
            if (entry.output == ICON_OUTPUT_PATH && spec.iconPng != null) continue
            val bytes = readAsset("$root/${entry.asset}")
            val content = if (entry.binary) {
                bytes
            } else {
                renderText(bytes.toString(Charsets.UTF_8), values).toByteArray(Charsets.UTF_8)
            }
            files += RenderedFile(
                path = renderText(entry.output, values),
                content = content,
                executable = entry.executable,
            )
        }
        val deletions = if (spec.iconPng != null) listOf(ICON_OUTPUT_PATH) else emptyList()
        spec.iconPng?.let { png ->
            files += RenderedFile(path = ICON_PNG_OUTPUT_PATH, content = png)
        }
        return RenderResult(files = files, deletions = deletions)
    }

    fun renderText(template: String, values: Map<String, String>): String {
        var out = template
        for ((key, value) in values) {
            out = out.replace("{{$key}}", value)
        }
        return out
    }

    /** Escapes text for use inside a double-quoted Kotlin string literal. */
    fun escapeKotlinString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("\n", "\\n")

    /** Escapes text for use as Android XML resource text (apostrophes included). */
    fun escapeXmlText(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")

    /** Turns an app name into a valid GitHub repository name. */
    fun repoNameFromAppName(appName: String): String {
        val slug = buildString {
            var lastDash = true
            for (c in appName.lowercase()) {
                if (c.code < 128 && (c.isLetterOrDigit())) {
                    append(c)
                    lastDash = false
                } else if (!lastDash) {
                    append('-')
                    lastDash = true
                }
            }
        }.trim('-')
        val trimmed = if (slug.length > 48) slug.take(48).substringBeforeLast('-') else slug
        return trimmed.ifBlank { "my-app" }
    }
}
