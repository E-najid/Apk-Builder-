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
 *
 * React Native is the strictest rule: every Node project has a package.json,
 * so the file alone proves nothing — the detector reads its content and only
 * flags RN when a "react-native" dependency is actually listed. Without a
 * file reader the RN rule stays silent (no false positives, per the rules).
 */
object ToolchainDetector {

    suspend fun detect(
        paths: Collection<String>,
        readFile: (suspend (String) -> String?)? = null,
    ): Set<Toolchain> {
        val fileNames = paths.map { it.substringAfterLast('/') }.toSet()
        val detected = mutableSetOf<Toolchain>()
        if ("Cargo.toml" in fileNames) detected += Toolchain.RUST
        if ("CMakeLists.txt" in fileNames) detected += Toolchain.NDK
        if (paths.any { it.endsWith(".py") }) detected += Toolchain.PYTHON
        if ("pubspec.yaml" in fileNames) detected += Toolchain.FLUTTER
        if (hasReactNativeDependency(paths, readFile)) detected += Toolchain.REACT_NATIVE
        return detected
    }

    /**
     * True when a package.json in the tree lists a "react-native"
     * dependency — a plain Node project (build script, server, anything)
     * must NOT be flagged.
     */
    private suspend fun hasReactNativeDependency(
        paths: Collection<String>,
        readFile: (suspend (String) -> String?)?,
    ): Boolean {
        val reader = readFile ?: return false
        val packageJsonPaths = paths.filter { it.substringAfterLast('/') == "package.json" }
        for (path in packageJsonPaths) {
            val content = reader(path) ?: continue
            // Quoted JSON key "react-native" — matches dependencies and
            // devDependencies alike; a plain Node project won't have it.
            if (Regex(""""react-native"\s*:""").containsMatchIn(content)) return true
        }
        return false
    }
}
