package com.enajid.apkbuilder.domain

/**
 * Client-side detection of extra toolchains in a project's file tree.
 *
 * v1 uses this only to show an honest "this may take longer" note on the build
 * screen. v2 will feed the same result into dynamic workflow generation (see
 * the README's "Workflow generation" section).
 *
 * Detection follows the project rules: prefer specific config files
 * (Cargo.toml, CMakeLists.txt, pubspec.yaml, package.json) over bare file
 * extensions to avoid false positives — with `.py` as the one documented
 * exception, since Python projects are identified by their scripts.
 */
object ToolchainDetector {

    fun detect(paths: Collection<String>): Set<Toolchain> {
        val fileNames = paths.map { it.substringAfterLast('/') }.toSet()
        val detected = mutableSetOf<Toolchain>()
        if ("Cargo.toml" in fileNames) detected += Toolchain.RUST
        if ("CMakeLists.txt" in fileNames) detected += Toolchain.NDK
        if (paths.any { it.endsWith(".py") }) detected += Toolchain.PYTHON
        if ("pubspec.yaml" in fileNames) detected += Toolchain.FLUTTER
        if ("package.json" in fileNames) detected += Toolchain.REACT_NATIVE
        return detected
    }
}
