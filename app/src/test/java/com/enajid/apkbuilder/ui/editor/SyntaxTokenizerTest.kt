package com.enajid.apkbuilder.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxTokenizerTest {

    private fun SyntaxSpan.text(code: String) = code.substring(start, end)

    @Test
    fun `kotlin keywords strings comments and numbers`() {
        val code = "val x = 42 // the answer\nfun main() { print(\"hi\") }"
        val spans = SyntaxTokenizer.tokenize(code, SyntaxLanguage.KOTLIN)

        assertTrue(spans.any { it.type == SpanType.KEYWORD && it.text(code) == "val" })
        assertTrue(spans.any { it.type == SpanType.KEYWORD && it.text(code) == "fun" })
        assertTrue(spans.any { it.type == SpanType.NUMBER && it.text(code) == "42" })
        assertTrue(spans.any { it.type == SpanType.COMMENT && it.text(code) == "// the answer" })
        assertTrue(spans.any { it.type == SpanType.STRING && it.text(code) == "\"hi\"" })
    }

    @Test
    fun `kotlin types annotations and block comments`() {
        val code = "/* header */ @Composable fun Foo(): Bundle = Bundle()"
        val spans = SyntaxTokenizer.tokenize(code, SyntaxLanguage.KOTLIN)

        assertTrue(spans.any { it.type == SpanType.COMMENT && it.text(code) == "/* header */" })
        assertTrue(spans.any { it.type == SpanType.ANNOTATION && it.text(code) == "@Composable" })
        assertTrue(spans.any { it.type == SpanType.TYPE && it.text(code) == "Bundle" })
    }

    @Test
    fun `xml tags attributes and values`() {
        val code = "<?xml version=\"1.0\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />"
        val spans = SyntaxTokenizer.tokenize(code, SyntaxLanguage.XML)

        assertTrue(spans.any { it.type == SpanType.PI })
        assertTrue(spans.any { it.type == SpanType.TAG && it.text(code) == "<manifest" })
        assertTrue(spans.any { it.type == SpanType.ATTR && it.text(code) == "xmlns:android" })
        assertTrue(spans.any { it.type == SpanType.STRING })
    }

    @Test
    fun `plain files produce no spans`() {
        assertTrue(SyntaxTokenizer.tokenize("anything at all", SyntaxLanguage.PLAIN).isEmpty())
    }

    @Test
    fun `language inference from file extensions`() {
        assertEquals(SyntaxLanguage.KOTLIN, inferLanguage("MainActivity.kt"))
        assertEquals(SyntaxLanguage.KOTLIN, inferLanguage("build.gradle.kts"))
        assertEquals(SyntaxLanguage.KOTLIN, inferLanguage("src/Main.java"))
        assertEquals(SyntaxLanguage.XML, inferLanguage("res/values/strings.xml"))
        assertEquals(SyntaxLanguage.PLAIN, inferLanguage("README.md"))
        assertEquals(SyntaxLanguage.PLAIN, inferLanguage("gradle.properties"))
    }

    @Test
    fun `spans stay inside the text bounds`() {
        val code = "class A { /* multi\nline */ val s = \"abc\" }"
        val spans = SyntaxTokenizer.tokenize(code, SyntaxLanguage.KOTLIN)
        assertTrue(spans.isNotEmpty())
        spans.forEach {
            assertTrue(it.start >= 0 && it.end <= code.length && it.start < it.end)
        }
    }
}
