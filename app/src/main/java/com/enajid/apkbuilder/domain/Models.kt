package com.enajid.apkbuilder.domain

/** Frameworks users can pick when creating a project. */
enum class Framework(val label: String, val available: Boolean, val blurb: String) {
    KOTLIN("Kotlin", true, "Modern Android app with Jetpack Compose"),
    JAVA("Java", true, "Classic Android app in Java — no Kotlin, pure Java"),
    FLUTTER("Flutter", true, "Flutter 3.47 app — Dart, single codebase"),
    REACT_NATIVE("React Native", true, "React Native 0.86 app — TypeScript + Hermes"),
}

/** Everything needed to render a fresh Kotlin project from the template. */
data class ProjectSpec(
    val appName: String,
    val packageName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val framework: Framework = Framework.KOTLIN,
    val iconPng: ByteArray? = null,
    val compileSdk: Int = 34,
)

/**
 * Extra toolchains detected in a project. v1 only uses this to show a "this
 * may take longer" note; v2 will also inject the matching workflow steps
 * (see WorkflowGenerator in the README).
 */
enum class Toolchain(val slowsBuildDown: Boolean, val label: String) {
    RUST(true, "Rust"),
    NDK(true, "native C/C++"),
    PYTHON(false, "Python"),
    FLUTTER(false, "Flutter"),
    REACT_NATIVE(false, "React Native"),
}
