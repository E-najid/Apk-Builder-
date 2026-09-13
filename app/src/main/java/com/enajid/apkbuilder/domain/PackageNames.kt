package com.enajid.apkbuilder.domain

/**
 * Validation and auto-suggestion of Android package names
 * (reverse-domain identifiers like `com.example.myapp`).
 */
object PackageNames {

    private val SEGMENT = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    private val KEYWORDS = setOf(
        // Kotlin + Java keywords that would break generated `package` statements.
        "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
        "final", "finally", "float", "for", "fun", "goto", "if", "implements", "import",
        "in", "infix", "init", "inline", "inner", "interface", "internal", "is", "long",
        "native", "new", "object", "open", "out", "override", "package", "private",
        "protected", "public", "reified", "return", "sealed", "short", "static", "strictfp",
        "super", "suspend", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "typealias", "typeof", "val", "var", "vararg", "void", "volatile", "when",
        "where", "while", "true", "false", "null", "it", "field", "by", "companion",
    )

    fun isValid(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.length > 120) return false
        val segments = trimmed.split('.')
        if (segments.size < 2) return false
        return segments.all { SEGMENT.matches(it) && it !in KEYWORDS }
    }

    fun validationError(packageName: String): String? {
        val trimmed = packageName.trim()
        return when {
            trimmed.isEmpty() -> null // let the empty state speak for itself
            !trimmed.contains('.') -> "Add at least two parts, like com.myapp"
            trimmed.split('.').any { it.isEmpty() } -> "Empty part — remove the extra dot"
            segments(trimmed).any { it[0].isDigit() } -> "Each part must start with a letter"
            segments(trimmed).any { !SEGMENT.matches(it) } ->
                "Only letters, digits and underscores are allowed"
            segments(trimmed).any { it in KEYWORDS } -> "Can't use a reserved keyword as a part"
            trimmed.length > 120 -> "That package name is too long"
            else -> null
        }
    }

    private fun segments(packageName: String): List<String> =
        packageName.split('.').filter { it.isNotEmpty() }

    /** Auto-suggests a package name from an app name, e.g. "My Cool App" -> com.mycoolapp. */
    fun suggestFromAppName(appName: String): String {
        val joined = appName.lowercase()
            .map { c -> if (c.code < 128 && (c.isLetterOrDigit())) c else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString("")
        val base = when {
            joined.length >= 3 -> joined
            joined.isNotEmpty() -> joined + "app"
            else -> "myapp"
        }
        val segment = sanitizeSegment(base) ?: "myapp"
        return "com.$segment"
    }

    private fun sanitizeSegment(segment: String): String? = when {
        segment.isEmpty() -> null
        segment[0].isDigit() -> "app$segment"
        segment in KEYWORDS -> "app$segment"
        !SEGMENT.matches(segment) -> null
        else -> segment
    }
}
