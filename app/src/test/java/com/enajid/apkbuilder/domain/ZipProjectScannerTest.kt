package com.enajid.apkbuilder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipProjectScannerTest {

    private fun s(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun kotlinProject(): Map<String, ByteArray> {
        val root = "MyApp" // wrapped by a file manager, as zips usually are
        return mapOf(
            "$root/settings.gradle.kts" to s("pluginManagement {}\n"),
            "$root/build.gradle.kts" to s("plugins {}\n"),
            "$root/app/build.gradle.kts" to s(
                """
                android {
                    namespace = "com.foo.bar"
                    compileSdk = 34
                    defaultConfig {
                        applicationId = "com.foo.bar"
                        minSdk = 24
                        targetSdk = 34
                    }
                }
                """.trimIndent()
            ),
            "$root/app/src/main/AndroidManifest.xml" to s("<manifest/>"),
            "$root/app/src/main/res/values/strings.xml" to s(
                "<resources><string name=\"app_name\">Foo Bar</string></resources>"
            ),
            "$root/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" to byteArrayOf(1, 2, 3),
            "$root/app/src/main/res/mipmap-mdpi/ic_launcher.png" to byteArrayOf(4),
            "$root/app/src/main/java/com/foo/bar/MainActivity.kt" to s("class MainActivity"),
            "$root/.github/workflows/build.yml" to s("name: Build APK\n"),
        )
    }

    @Test
    fun `detects a wrapped kotlin project end to end`() {
        val result = ZipProjectScanner.scan(kotlinProject())

        assertTrue(result.valid)
        assertEquals("Foo Bar", result.appName)
        assertEquals("com.foo.bar", result.packageName)
        assertEquals(24, result.minSdk)
        assertEquals(34, result.targetSdk)
        assertEquals(Framework.KOTLIN, result.framework)
        assertTrue(result.isGradleProject)
        assertTrue(result.hasOwnWorkflow)
        assertNotNull(result.iconBytes)
        // Best density wins.
        assertTrue(result.iconBytes!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `falls back to the manifest label when there is no strings xml`() {
        val files = mapOf(
            "AndroidManifest.xml" to s(
                "<manifest package=\"com.baz.app\"><application android:label=\"Baz App\"/></manifest>"
            ),
            "app/src/main/java/Main.java" to s("class Main {}"),
            "app/src/main/java/Other.java" to s("class Other {}"),
        )
        val result = ZipProjectScanner.scan(files)

        assertTrue(result.valid)
        assertEquals("Baz App", result.appName)
        assertEquals("com.baz.app", result.packageName)
        assertEquals(Framework.JAVA, result.framework)
        assertNull(result.minSdk)
    }

    @Test
    fun `detects flutter and react native`() {
        val flutter = mapOf(
            "pubspec.yaml" to s("name: flut\n"),
            "android/app/build.gradle" to s("defaultConfig { applicationId 'com.flut.app' minSdkVersion 21 }"),
        )
        assertEquals(Framework.FLUTTER, ZipProjectScanner.scan(flutter).framework)
        assertEquals(21, ZipProjectScanner.scan(flutter).minSdk)

        val rn = mapOf(
            "package.json" to s("""{"dependencies":{"react-native":"0.72.0"}}"""),
            "android/app/src/main/AndroidManifest.xml" to s("<manifest/>"),
        )
        assertEquals(Framework.REACT_NATIVE, ZipProjectScanner.scan(rn).framework)
    }

    @Test
    fun `rejects zips with nothing recognizable`() {
        val result = ZipProjectScanner.scan(mapOf("notes.txt" to s("hello")))
        assertFalse(result.valid)
        assertNotNull(result.invalidReason)
        assertTrue(result.invalidReason!!.contains("Couldn’t find", ignoreCase = true))
    }

    @Test
    fun `groovy gradle syntax is parsed too`() {
        val files = mapOf(
            "settings.gradle" to s("include ':app'\n"),
            "build.gradle" to s("// top\n"),
            "app/build.gradle" to s(
                "android { defaultConfig { applicationId \"com.groovy.app\" minSdkVersion 19 targetSdkVersion 33 } }"
            ),
            "app/src/main/AndroidManifest.xml" to s("<manifest/>"),
        )
        val result = ZipProjectScanner.scan(files)
        assertTrue(result.valid)
        assertEquals("com.groovy.app", result.packageName)
        assertEquals(19, result.minSdk)
        assertEquals(33, result.targetSdk)
    }
}

class UploadFilterTest {

    @Test
    fun `excludes build outputs, caches and signing material`() {
        assertTrue(UploadFilter.isExcluded("app/build/outputs/apk/app.apk"))
        assertTrue(UploadFilter.isExcluded(".git/config"))
        assertTrue(UploadFilter.isExcluded(".gradle/8.8/cache.bin"))
        assertTrue(UploadFilter.isExcluded("node_modules/react-native/index.js"))
        assertTrue(UploadFilter.isExcluded("release.keystore"))
        assertTrue(UploadFilter.isExcluded("signing.jks"))
        assertTrue(UploadFilter.isExcluded("local.properties"))
        assertTrue(UploadFilter.isExcluded("app.iml"))
        assertTrue(UploadFilter.isExcluded(".DS_Store"))
    }

    @Test
    fun `keeps real source files`() {
        assertFalse(UploadFilter.isExcluded("app/src/main/java/com/example/MainActivity.kt"))
        assertFalse(UploadFilter.isExcluded(".github/workflows/build.yml"))
        assertFalse(UploadFilter.isExcluded("gradle/wrapper/gradle-wrapper.properties"))
        assertFalse(UploadFilter.isExcluded("app/src/main/res/values/strings.xml"))
    }
}

class ProjectFilesTest {

    @Test
    fun `files the build needs are protected`() {
        assertTrue(ProjectFiles.isCriticalToBuild("settings.gradle.kts"))
        assertTrue(ProjectFiles.isCriticalToBuild("build.gradle"))
        assertTrue(ProjectFiles.isCriticalToBuild("app/build.gradle.kts"))
        assertTrue(ProjectFiles.isCriticalToBuild("app/src/main/AndroidManifest.xml"))
        assertTrue(ProjectFiles.isCriticalToBuild("gradle/wrapper/gradle-wrapper.jar"))
        assertTrue(ProjectFiles.isCriticalToBuild("gradlew"))
        assertTrue(ProjectFiles.isCriticalToBuild(".github/workflows/build.yml"))
        assertNotNull(ProjectFiles.criticalReason("gradlew"))
    }

    @Test
    fun `ordinary files are deletable`() {
        assertFalse(ProjectFiles.isCriticalToBuild("app/src/main/java/com/example/Util.kt"))
        assertFalse(ProjectFiles.isCriticalToBuild("app/src/main/res/values/extra.xml"))
        assertNull(ProjectFiles.criticalReason("app/src/main/java/com/example/Util.kt"))
    }
}
