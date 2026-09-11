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
        Framework.REACT_NATIVE -> "templates/react-native-app"
        Framework.FLUTTER -> "templates/flutter-app"
    }

    private fun entriesFor(framework: Framework): List<Entry> = when (framework) {
        Framework.KOTLIN -> kotlinEntries
        Framework.JAVA -> javaEntries
        Framework.REACT_NATIVE -> reactNativeEntries
        Framework.FLUTTER -> flutterEntries
    }

    /**
     * Launcher icon paths. Kotlin/Java keep the res dir at the repo root;
     * RN/Flutter nest it under android/app/.
     */
    fun iconXmlPath(framework: Framework): String = when (framework) {
        Framework.KOTLIN, Framework.JAVA -> ICON_OUTPUT_PATH
        else -> "android/app/src/main/res/drawable/ic_launcher.xml"
    }

    fun iconPngPath(framework: Framework): String =
        iconXmlPath(framework).removeSuffix(".xml") + ".png"


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

    private val reactNativeEntries = listOf(
        Entry("dot-github/workflows/build.yml", ".github/workflows/build.yml"),
        Entry("dot-gitignore", ".gitignore"),
        Entry("dot-watchmanconfig", ".watchmanconfig"),
        Entry("README.md", "README.md"),
        Entry("package.json", "package.json"),
        Entry("app.json", "app.json"),
        Entry("index.js", "index.js"),
        Entry("App.tsx", "App.tsx"),
        Entry("babel.config.js", "babel.config.js"),
        Entry("metro.config.js", "metro.config.js"),
        Entry("android/settings.gradle", "android/settings.gradle"),
        Entry("android/build.gradle", "android/build.gradle"),
        Entry("android/gradle.properties", "android/gradle.properties"),
        Entry("android/gradlew", "android/gradlew", executable = true),
        Entry("android/gradlew.bat", "android/gradlew.bat"),
        Entry("android/gradle/wrapper/gradle-wrapper.jar", "android/gradle/wrapper/gradle-wrapper.jar", binary = true),
        Entry("android/gradle/wrapper/gradle-wrapper.properties", "android/gradle/wrapper/gradle-wrapper.properties"),
        Entry("android/app/build.gradle", "android/app/build.gradle"),
        Entry("android/app/proguard-rules.pro", "android/app/proguard-rules.pro"),
        Entry("android/app/debug.keystore", "android/app/debug.keystore", binary = true),
        Entry("android/app/src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml"),
        Entry("android/app/src/main/java/MainActivity.kt", "android/app/src/main/java/{{PACKAGE_PATH}}/MainActivity.kt"),
        Entry("android/app/src/main/java/MainApplication.kt", "android/app/src/main/java/{{PACKAGE_PATH}}/MainApplication.kt"),
        Entry("android/app/src/main/res/drawable/ic_launcher.xml", "android/app/src/main/res/drawable/ic_launcher.xml"),
        Entry("android/app/src/main/res/drawable/rn_edit_text_material.xml", "android/app/src/main/res/drawable/rn_edit_text_material.xml"),
        Entry("android/app/src/main/res/values/strings.xml", "android/app/src/main/res/values/strings.xml"),
        Entry("android/app/src/main/res/values/styles.xml", "android/app/src/main/res/values/styles.xml"),
    )

    private val flutterEntries = listOf(
        Entry("dot-github/workflows/build.yml", ".github/workflows/build.yml"),
        Entry("dot-gitignore", ".gitignore"),
        Entry("README.md", "README.md"),
        Entry("pubspec.yaml", "pubspec.yaml"),
        Entry("analysis_options.yaml", "analysis_options.yaml"),
        Entry("lib/main.dart", "lib/main.dart"),
        // Note: `android/dot-gitignore` — a real `.gitignore` asset name would
        // be silently dropped from the APK by aapt's dot-file ignore pattern.
        Entry("android/dot-gitignore", "android/.gitignore"),
        Entry("android/settings.gradle.kts", "android/settings.gradle.kts"),
        Entry("android/build.gradle.kts", "android/build.gradle.kts"),
        Entry("android/gradle.properties", "android/gradle.properties"),
        Entry("android/gradle/wrapper/gradle-wrapper.properties", "android/gradle/wrapper/gradle-wrapper.properties"),
        Entry("android/app/build.gradle.kts", "android/app/build.gradle.kts"),
        Entry("android/app/src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml"),
        Entry("android/app/src/debug/AndroidManifest.xml", "android/app/src/debug/AndroidManifest.xml"),
        Entry("android/app/src/profile/AndroidManifest.xml", "android/app/src/profile/AndroidManifest.xml"),
        Entry("android/app/src/main/kotlin/MainActivity.kt", "android/app/src/main/kotlin/{{PACKAGE_PATH}}/MainActivity.kt"),
        Entry("android/app/src/main/res/drawable/ic_launcher.xml", "android/app/src/main/res/drawable/ic_launcher.xml"),
        Entry("android/app/src/main/res/drawable/launch_background.xml", "android/app/src/main/res/drawable/launch_background.xml"),
        Entry("android/app/src/main/res/drawable-v21/launch_background.xml", "android/app/src/main/res/drawable-v21/launch_background.xml"),
        Entry("android/app/src/main/res/values/styles.xml", "android/app/src/main/res/values/styles.xml"),
        Entry("android/app/src/main/res/values-night/styles.xml", "android/app/src/main/res/values-night/styles.xml"),
    )

    fun render(spec: ProjectSpec, readAsset: (String) -> ByteArray): RenderResult {
        val root = templateRoot(spec.framework)
        val entries = entriesFor(spec.framework)
        val values = mapOf(
            "APP_NAME" to spec.appName,
            "APP_NAME_KOTLIN" to escapeKotlinString(spec.appName),
            "APP_NAME_XML" to escapeXmlText(spec.appName),
            "APP_JS_NAME" to jsIdentifierFrom(spec.appName),
            "PUBSPEC_NAME" to dartIdentifierFrom(spec.appName),
            "PACKAGE_NAME" to spec.packageName,
            "PACKAGE_PATH" to spec.packageName.replace('.', '/'),
            "MIN_SDK" to spec.minSdk.toString(),
            "TARGET_SDK" to spec.targetSdk.toString(),
            "COMPILE_SDK" to spec.compileSdk.toString(),
        )
        val iconXml = iconXmlPath(spec.framework)
        val iconPng = iconPngPath(spec.framework)
        val files = mutableListOf<RenderedFile>()
        for (entry in entries) {
            // A custom icon replaces the default vector drawable.
            if (entry.output == iconXml && spec.iconPng != null) continue
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
        val deletions = if (spec.iconPng != null) listOf(iconXml) else emptyList()
        spec.iconPng?.let { png ->
            files += RenderedFile(path = iconPng, content = png)
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

    /**
     * Turns an app name into a safe JS identifier (used for the RN component
     * name, app.json name and root Gradle project name):
     * "My Cool App!" -> "MyCoolApp", "2 Fast" -> "App2Fast".
     */
    fun jsIdentifierFrom(appName: String): String {
        val ident = appName.filter { it.code < 128 && (it.isLetterOrDigit()) }
            .take(50)
        return if (ident.isEmpty() || ident.first().isDigit()) "App$ident" else ident
    }

    /**
     * Turns an app name into a valid Dart package name (lower_snake_case):
     * "My Cool App" -> "my_cool_app", "2 Fast" -> "app_2_fast".
     */
    fun dartIdentifierFrom(appName: String): String {
        val ident = buildString {
            var lastUnderscore = true
            for (c in appName.lowercase()) {
                if (c.code < 128 && (c.isLetterOrDigit())) {
                    append(c)
                    lastUnderscore = false
                } else if (!lastUnderscore) {
                    append('_')
                    lastUnderscore = true
                }
            }
        }.trim('_').take(60)
        return when {
        ident.isEmpty() -> "app"
        ident.first().isDigit() -> "app_$ident"
        else -> ident
    }
    }
}
