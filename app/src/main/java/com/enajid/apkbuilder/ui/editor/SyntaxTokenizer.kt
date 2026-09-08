package com.enajid.apkbuilder.ui.editor

import java.util.regex.Pattern

/** Which highlighting grammar to use for a file. */
enum class SyntaxLanguage { KOTLIN, XML, PLAIN }

/** Categories of highlighted tokens. */
enum class SpanType { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, TYPE, TAG, ATTR, PI }

data class SyntaxSpan(val start: Int, val end: Int, val type: SpanType)

fun inferLanguage(path: String): SyntaxLanguage = when {
    path.endsWith(".kt") || path.endsWith(".kts") || path.endsWith(".java") -> SyntaxLanguage.KOTLIN
    path.endsWith(".xml") -> SyntaxLanguage.XML
    else -> SyntaxLanguage.PLAIN
}

/**
 * Small, dependency-free tokenizer for Kotlin/Java and XML. Single pass with
 * one combined regex per language — fast enough to re-run on every keystroke
 * for the file sizes this editor deals with.
 */
object SyntaxTokenizer {

    private val KOTLIN_KEYWORDS = setOf(
        // Kotlin
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
        "in", "interface", "is", "null", "object", "package", "return", "super", "this",
        "throw", "true", "try", "typealias", "val", "var", "when", "while", "by", "where",
        "abstract", "actual", "annotation", "companion", "const", "crossinline", "data",
        "dynamic", "enum", "expect", "external", "final", "infix", "init", "inline",
        "inner", "internal", "lateinit", "noinline", "open", "out", "override", "private",
        "protected", "public", "reified", "sealed", "suspend", "tailrec", "value", "vararg",
        // Java
        "boolean", "byte", "char", "default", "double", "extends", "finally", "float",
        "implements", "import", "instanceof", "int", "long", "native", "new", "short",
        "static", "strictfp", "switch", "synchronized", "transient", "void", "volatile",
        "assert", "goto",
    )

    // Which named groups each pattern actually defines. Matcher.group(name)
    // throws for names the pattern doesn't contain, so these lists must match.
    private val KOTLIN_GROUPS = listOf("comment", "str", "annotation", "number", "ident")
    private val XML_GROUPS = listOf("comment", "pi", "tag", "attr", "str")

    // Order matters: comments and strings must win over identifiers/numbers.
    private val KOTLIN_PATTERN: Pattern = Pattern.compile(
        "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<str>\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')" +
            "|(?<annotation>@[A-Za-z_][A-Za-z0-9_]*)" +
            "|(?<number>\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.[\\d_]+)?(?:[eE][+-]?\\d+)?[fFlLdD]?)\\b)" +
            "|(?<ident>[A-Za-z_][A-Za-z0-9_]*)"
    )

    private val XML_PATTERN: Pattern = Pattern.compile(
        "(?<comment><!--[\\s\\S]*?-->)" +
            "|(?<pi><\\?[\\s\\S]*?\\?>)" +
            "|(?<tag></?[A-Za-z][\\w.:-]*)" +
            "|(?<attr>[A-Za-z_][\\w.:-]*(?=\\s*=))" +
            "|(?<str>\"[^\"\\n]*\")"
    )

    fun tokenize(code: String, language: SyntaxLanguage): List<SyntaxSpan> = when (language) {
        SyntaxLanguage.PLAIN -> emptyList()
        SyntaxLanguage.KOTLIN -> tokenizeWith(KOTLIN_PATTERN, KOTLIN_GROUPS, code) { group, value ->
            when (group) {
                "comment" -> SpanType.COMMENT
                "str" -> SpanType.STRING
                "annotation" -> SpanType.ANNOTATION
                "number" -> SpanType.NUMBER
                "ident" -> when {
                    value in KOTLIN_KEYWORDS -> SpanType.KEYWORD
                    value.isNotEmpty() && value[0].isUpperCase() -> SpanType.TYPE
                    else -> null
                }
                else -> null
            }
        }
        SyntaxLanguage.XML -> tokenizeWith(XML_PATTERN, XML_GROUPS, code) { group, _ ->
            when (group) {
                "comment" -> SpanType.COMMENT
                "pi" -> SpanType.PI
                "tag" -> SpanType.TAG
                "attr" -> SpanType.ATTR
                "str" -> SpanType.STRING
                else -> null
            }
        }
    }

    private inline fun tokenizeWith(
        pattern: Pattern,
        groupNames: List<String>,
        code: String,
        classify: (group: String, value: String) -> SpanType?,
    ): List<SyntaxSpan> {
        val spans = mutableListOf<SyntaxSpan>()
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            var type: SpanType? = null
            for (name in groupNames) {
                val value = matcher.group(name)
                if (value != null) {
                    type = classify(name, value)
                    break
                }
            }
            if (type != null) {
                spans += SyntaxSpan(matcher.start(), matcher.end(), type)
            }
        }
        return spans
    }
}
