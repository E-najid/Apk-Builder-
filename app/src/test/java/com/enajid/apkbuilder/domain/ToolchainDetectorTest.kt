package com.enajid.apkbuilder.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainDetectorTest {

    @Test
    fun `detects rust via Cargo dot toml`() {
        val toolchains = ToolchainDetector.detect(listOf("Cargo.toml", "src/main.rs", "README.md"))
        assertTrue(Toolchain.RUST in toolchains)
    }

    @Test
    fun `detects ndk via CMakeLists`() {
        val toolchains = ToolchainDetector.detect(listOf("app/CMakeLists.txt", "app/src/main/cpp/lib.cpp"))
        assertTrue(Toolchain.NDK in toolchains)
    }

    @Test
    fun `file extensions alone do not trigger the ndk rule`() {
        // Per the project rules: detect via config files, not bare extensions,
        // to avoid false positives.
        val toolchains = ToolchainDetector.detect(listOf("docs/architecture.cpp.example.md", "src/thing.h"))
        assertFalse(Toolchain.NDK in toolchains)
        assertTrue(toolchains.isEmpty())
    }

    @Test
    fun `detects flutter python and react native`() {
        assertTrue(Toolchain.FLUTTER in ToolchainDetector.detect(listOf("pubspec.yaml")))
        assertTrue(Toolchain.PYTHON in ToolchainDetector.detect(listOf("app/src/main/python/script.py")))
        assertTrue(Toolchain.REACT_NATIVE in ToolchainDetector.detect(listOf("package.json", "index.js")))
    }

    @Test
    fun `plain kotlin project detects nothing`() {
        val paths = listOf(
            ".github/workflows/build.yml",
            "app/build.gradle.kts",
            "app/src/main/java/com/example/app/MainActivity.kt",
            "app/src/main/res/values/strings.xml",
        )
        assertTrue(ToolchainDetector.detect(paths).isEmpty())
    }
}
