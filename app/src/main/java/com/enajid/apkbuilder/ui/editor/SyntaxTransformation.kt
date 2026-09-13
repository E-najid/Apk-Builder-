package com.enajid.apkbuilder.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Color palette for the code editor, in light and dark flavors. */
data class SyntaxColors(
    val background: Color,
    val gutter: Color,
    val gutterEdge: Color,
    val plain: Color,
    val lineNumber: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val type: Color,
    val tag: Color,
    val attr: Color,
    val cursor: Color,
    val selection: Color,
) {
    companion object {
        fun dark() = SyntaxColors(
            background = Color(0xFF17171E),
            gutter = Color(0xFF1F1F28),
            gutterEdge = Color(0xFF2C2C3A),
            plain = Color(0xFFD6D7DE),
            lineNumber = Color(0xFF56566A),
            keyword = Color(0xFFC792EA),
            string = Color(0xFFA5E075),
            comment = Color(0xFF6A9955),
            number = Color(0xFFF78C6C),
            annotation = Color(0xFFFFCB6B),
            type = Color(0xFF82AAFF),
            tag = Color(0xFFF07178),
            attr = Color(0xFFFFCB6B),
            cursor = Color(0xFF7C9AFF),
            selection = Color(0xFF33427A),
        )

        fun light() = SyntaxColors(
            background = Color(0xFFFCFCFE),
            gutter = Color(0xFFF0F0F5),
            gutterEdge = Color(0xFFE1E1E8),
            plain = Color(0xFF24242C),
            lineNumber = Color(0xFF9E9EB0),
            keyword = Color(0xFF8250DF),
            string = Color(0xFF0A7D34),
            comment = Color(0xFF6E7781),
            number = Color(0xFF956500),
            annotation = Color(0xFF9A6700),
            type = Color(0xFF0550AE),
            tag = Color(0xFFB31D28),
            attr = Color(0xFF953800),
            cursor = Color(0xFF4355B9),
            selection = Color(0xFFCCD5FF),
        )
    }
}

/**
 * Applies syntax highlighting as a VisualTransformation. Only span styles
 * change — never the text or its length — so the offset mapping is identity.
 */
class SyntaxTransformation(
    private val language: SyntaxLanguage,
    private val colors: SyntaxColors,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (language == SyntaxLanguage.PLAIN) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val spans = SyntaxTokenizer.tokenize(text.text, language)
        if (spans.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(text)
        for (span in spans) {
            val color = when (span.type) {
                SpanType.KEYWORD -> colors.keyword
                SpanType.STRING -> colors.string
                SpanType.COMMENT -> colors.comment
                SpanType.NUMBER -> colors.number
                SpanType.ANNOTATION -> colors.annotation
                SpanType.TYPE -> colors.type
                SpanType.TAG -> colors.tag
                SpanType.ATTR -> colors.attr
                SpanType.PI -> colors.comment
            }
            builder.addStyle(SpanStyle(color = color), span.start, span.end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
