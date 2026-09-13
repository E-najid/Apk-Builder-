package com.enajid.apkbuilder.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainDetectorTest {

    private fun detect(
        paths: List<String>,
        packageJson: String? = null,
    ): Set<Toolchain> = runBlocking {
        ToolchainDetector.detect(paths) { path ->
            if (path.substringAfterLast('/') == "package.json") packageJson else null
        }
    }

    @Test
    fun `detects rust via Cargo dot toml`() {
        val toolchains = detect(listOf("Cargo.toml", "src/main.rs", "README.md"))
        assertTrue(Toolchain.RUST in toolchains)
    }

    @Test
    fun `detects ndk via CMakeLists`() {
        val toolchains = detect(listOf("app/CMakeLists.txt", "app/src/main/cpp/lib.cpp"))
        assertTrue(Toolchain.NDK in toolchains)
    }

    @Test
    fun `file extensions alone do not trigger the ndk rule`() {
        // Per the project rules: detect via config files, not bare extensions,
        // to avoid false positives.
        val toolchains = detect(listOf("docs/architecture.cpp.example.md", "src/thing.h"))
        assertFalse(Toolchain.NDK in toolchains)
        assertTrue(toolchains.isEmpty())
    }

    @Test
    fun `detects flutter python and react native`() {
        assertTrue(Toolchain.FLUTTER in detect(listOf("pubspec.yaml")))
        assertTrue(Toolchain.PYTHON in detect(listOf("app/src/main/python/script.py")))
        assertTrue(
            Toolchain.REACT_NATIVE in detect(
                listOf("package.json", "index.js"),
                packageJson = """{"dependencies": {"react-native": "0.86.3"}}""",
            ),
        )
    }

    @Test
    fun `plain node project does not trigger react native`() {
        // A package.json alone proves nothing — any Node project has one.
        val toolchains = detect(
            listOf("package.json", "build.js", "README.md"),
            packageJson = """{"name": "site", "scripts": {"build": "node build.js"}}""",
        )
        assertFalse(Toolchain.REACT_NATIVE in toolchains)
        assertTrue(toolchains.isEmpty())
    }

    @Test
    fun `react native in devDependencies counts too`() {
        val toolchains = detect(
            listOf("package.json"),
            packageJson = """{"devDependencies": {"react-native": "^0.86.0"}}""",
        )
        assertTrue(Toolchain.REACT_NATIVE in toolchains)
    }

    @Test
    fun `without a file reader the react native rule stays silent`() {
        // No false positives when contents can't be read.
        val toolchains = runBlocking { ToolchainDetector.detect(listOf("package.json", "index.js")) }
        assertFalse(Toolchain.REACT_NATIVE in toolchains)
    }

    @Test
    fun `plain kotlin project detects nothing`() {
        val paths = listOf(
            ".github/workflows/build.yml",
            "app/build.gradle.kts",
            "app/src/main/java/com/example/app/MainActivity.kt",
            "app/src/main/res/values/strings.xml",
        )
        assertTrue(detect(paths).isEmpty())
    }
}
