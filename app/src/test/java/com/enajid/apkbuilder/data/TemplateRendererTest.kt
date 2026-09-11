package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.Framework
import com.enajid.apkbuilder.domain.ProjectSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRendererTest {

    private fun fakeAssets(): Map<String, ByteArray> {
        fun s(value: String) = value.toByteArray(Charsets.UTF_8)
        val root = TemplateRenderer.TEMPLATE_ROOT
        return mapOf(
            "$root/dot-github/workflows/build.yml" to s("name: Build APK\n"),
            "$root/dot-gitignore" to s("/build\n"),
            "$root/README.md" to s("# {{APP_NAME}}\n"),
            "$root/settings.gradle.kts" to s("rootProject.name = \"{{APP_NAME_KOTLIN}}\"\ninclude(\":app\")\n"),
            "$root/build.gradle.kts" to s("plugins { /* versions */ }\n"),
            "$root/gradle.properties" to s("android.useAndroidX=true\n"),
            "$root/gradlew" to s("#!/bin/sh\n"),
            "$root/gradlew.bat" to s("@echo off\n"),
            "$root/gradle/wrapper/gradle-wrapper.jar" to byteArrayOf(1, 2, 3, 4),
            "$root/gradle/wrapper/gradle-wrapper.properties" to s("distributionUrl=gradle-8.8-bin.zip\n"),
            "$root/app/build.gradle.kts" to s(
                "namespace = \"{{PACKAGE_NAME}}\"\ncompileSdk = {{COMPILE_SDK}}\n" +
                    "minSdk = {{MIN_SDK}}\ntargetSdk = {{TARGET_SDK}}\n"
            ),
            "$root/app/proguard-rules.pro" to s("# rules\n"),
            "$root/app/src/main/AndroidManifest.xml" to s("<manifest/>"),
            "$root/app/src/main/java/MainActivity.kt" to s("package {{PACKAGE_NAME}}\n\nclass MainActivity\n"),
            "$root/app/src/main/res/drawable/ic_launcher.xml" to s("<vector/>"),
            "$root/app/src/main/res/values/strings.xml" to s("<string name=\"app_name\">{{APP_NAME_XML}}</string>"),
            "$root/app/src/main/res/values/themes.xml" to s("<style/>"),
        )
    }

    private val spec = ProjectSpec(
        appName = "My Cool App",
        packageName = "com.mycoolapp",
        minSdk = 24,
        targetSdk = 34,
        framework = Framework.KOTLIN,
    )

    @Test
    fun `renders package path and all placeholders`() {
        val assets = fakeAssets()
        val result = TemplateRenderer.render(spec) { assets.getValue(it) }

        assertEquals(assets.size, result.files.size)
        assertTrue(result.deletions.isEmpty())

        val main = result.files.first { it.path == "app/src/main/java/com/mycoolapp/MainActivity.kt" }
        assertTrue(main.asText().contains("package com.mycoolapp"))

        val settings = result.files.first { it.path == "settings.gradle.kts" }
        assertTrue(settings.asText().contains("rootProject.name = \"My Cool App\""))

        val strings = result.files.first { it.path == "app/src/main/res/values/strings.xml" }
        assertTrue(strings.asText().contains("My Cool App"))

        val gradle = result.files.first { it.path == "app/build.gradle.kts" }
        assertTrue(gradle.asText().contains("namespace = \"com.mycoolapp\""))
        assertTrue(gradle.asText().contains("compileSdk = 34"))
        assertTrue(gradle.asText().contains("minSdk = 24"))
        assertTrue(gradle.asText().contains("targetSdk = 34"))

        val workflow = result.files.first { it.path == ".github/workflows/build.yml" }
        assertTrue(workflow.asText().contains("name: Build APK"))

        val gradlew = result.files.first { it.path == "gradlew" }
        assertTrue(gradlew.executable)
    }

    @Test
    fun `custom icon replaces the vector drawable`() {
        val assets = fakeAssets()
        val withIcon = spec.copy(iconPng = byteArrayOf(9, 9, 9))
        val result = TemplateRenderer.render(withIcon) { assets.getValue(it) }

        assertTrue(result.files.none { it.path == TemplateRenderer.ICON_OUTPUT_PATH })
        assertTrue(result.files.any { it.path == TemplateRenderer.ICON_PNG_OUTPUT_PATH })
        assertEquals(listOf(TemplateRenderer.ICON_OUTPUT_PATH), result.deletions)
        assertEquals(assets.size, result.files.size) // png replaces the xml 1:1
    }

    @Test
    fun `java template renders with package path and no kotlin files`() {
        val root = "templates/java-app"
        val assets = mapOf(
            "$root/dot-github/workflows/build.yml" to "name: Build\n".toByteArray(),
            "$root/dot-gitignore" to "/build\n".toByteArray(),
            "$root/README.md" to "# {{APP_NAME}}\n".toByteArray(),
            "$root/settings.gradle.kts" to "rootProject.name = \"{{APP_NAME_KOTLIN}}\"\n".toByteArray(),
            "$root/build.gradle.kts" to "plugins { }\n".toByteArray(),
            "$root/gradle.properties" to "android.useAndroidX=true\n".toByteArray(),
            "$root/gradlew" to "#!/bin/sh\n".toByteArray(),
            "$root/gradlew.bat" to "@echo off\n".toByteArray(),
            "$root/gradle/wrapper/gradle-wrapper.jar" to byteArrayOf(1, 2, 3),
            "$root/gradle/wrapper/gradle-wrapper.properties" to "distributionUrl=x\n".toByteArray(),
            "$root/app/build.gradle.kts" to "applicationId = \"{{PACKAGE_NAME}}\"\n".toByteArray(),
            "$root/app/proguard-rules.pro" to "\n".toByteArray(),
            "$root/app/src/main/AndroidManifest.xml" to "<manifest/>\n".toByteArray(),
            "$root/app/src/main/java/MainActivity.java" to "package {{PACKAGE_NAME}};\n".toByteArray(),
            "$root/app/src/main/res/drawable/ic_launcher.xml" to "<vector/>\n".toByteArray(),
            "$root/app/src/main/res/values/strings.xml" to "<string name=\"app_name\">{{APP_NAME_XML}}</string>\n".toByteArray(),
            "$root/app/src/main/res/values/themes.xml" to "<resources/>\n".toByteArray(),
        )
        val spec = ProjectSpec(
            appName = "Test Java",
            packageName = "com.enajid.testjava",
            minSdk = 24,
            targetSdk = 35,
            framework = Framework.JAVA,
        )
        val result = TemplateRenderer.render(spec) { assets.getValue(it) }

        val activity = result.files.first { it.path == "app/src/main/java/com/enajid/testjava/MainActivity.java" }
        assertEquals("package com.enajid.testjava;", activity.asText().trim())
        // no Kotlin/Compose files sneak in from the Kotlin template
        assertTrue(result.files.none { it.path.endsWith(".kt") })
        // gradlew keeps its executable bit
        assertTrue(result.files.first { it.path == "gradlew" }.executable)
    }

    @Test
    fun `escapes app names for kotlin string literals`() {
        assertEquals("My \\\"Cool\\\" App", TemplateRenderer.escapeKotlinString("My \"Cool\" App"))
        assertEquals("a\\\$b", TemplateRenderer.escapeKotlinString("a\$b"))
        assertEquals("line1\\nline2", TemplateRenderer.escapeKotlinString("line1\nline2"))
    }

    @Test
    fun `escapes app names for xml resources`() {
        assertEquals("a &amp; b", TemplateRenderer.escapeXmlText("a & b"))
        assertEquals("it\\'s &lt;great&gt;", TemplateRenderer.escapeXmlText("it's <great>"))
    }

    @Test
    fun `repo names are slugged from app names`() {
        assertEquals("my-cool-app", TemplateRenderer.repoNameFromAppName("My Cool App!"))
        assertEquals("my-app", TemplateRenderer.repoNameFromAppName("!!!"))
        assertEquals("app2", TemplateRenderer.repoNameFromAppName("app2"))
        assertEquals("caf-2024", TemplateRenderer.repoNameFromAppName("Café 2024"))
    }
}
