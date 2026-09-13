package com.enajid.apkbuilder.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * The native code editor.
 *
 * Design notes (see README "Why a native editor"):
 *  - one BasicTextField with fixed line height, sized to its content;
 *  - the whole surface scrolls (vertical + horizontal) around it;
 *  - the line-number gutter is a thin overlay that reads the scroll offset and
 *    only draws the visible numbers, so it stays cheap even for long files;
 *  - syntax highlighting is a VisualTransformation (no text rewrites).
 */
@Composable
fun CodeEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: SyntaxLanguage,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    highlightEnabled: Boolean = true,
) {
    val darkTheme = isSystemInDarkTheme()
    val colors = remember(darkTheme) { if (darkTheme) SyntaxColors.dark() else SyntaxColors.light() }
    val transformation = remember(language, colors) { SyntaxTransformation(language, colors) }
    val textMeasurer = rememberTextMeasurer()

    val fontSize = 13.sp
    val lineHeight = 20.sp
    val topPadding = 8.dp

    val codeStyle = remember(colors) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            lineHeight = lineHeight,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
            color = colors.plain,
        )
    }
    val numberStyle = remember(colors) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = lineHeight,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
            color = colors.lineNumber,
        )
    }

    val density = LocalDensity.current
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val longestLine = remember(value.text) { value.text.lines().maxOfOrNull { it.length } ?: 0 }

    val gutterWidth = 44.dp

    val contentWidth = remember(longestLine, codeStyle, density) {
        with(density) {
            val measured = textMeasurer.measure(
                "M".repeat(longestLine.coerceIn(1, 4000)),
                codeStyle
            ).size.width
            (measured + 64.dp.toPx()).toDp()
        }
    }
    val contentHeight = remember(lineCount, density) {
        with(density) {
            (lineCount * lineHeight.toPx() + 2 * topPadding.toPx()).toDp()
        }
    }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val numberCache = remember(numberStyle) { mutableStateMapOf<Int, TextLayoutResult>() }

    // Keep the caret roughly on screen while typing.
    LaunchedEffect(value.selection.min, value.text) {
        val before = value.text.take(value.selection.min)
        val line = before.count { it == '\n' }
        with(density) {
            val lineH = lineHeight.toPx()
            val contentH = lineCount * lineH + 2 * topPadding.toPx()
            val viewportH = (contentH - verticalScroll.maxValue).coerceAtLeast(lineH)
            val targetY = (line * lineH - viewportH / 3).coerceIn(0f, verticalScroll.maxValue.toFloat())
            verticalScroll.scrollTo(targetY.toInt())

            val charWidth = textMeasurer.measure("M", codeStyle).size.width.coerceAtLeast(1)
            val column = before.length - (before.lastIndexOf('\n') + 1)
            val caretX = column * charWidth
            val contentW = contentWidth.toPx()
            val viewportW = (contentW - horizontalScroll.maxValue).coerceAtLeast(charWidth.toFloat())
            val targetX = (caretX - viewportW / 2).coerceIn(0f, horizontalScroll.maxValue.toFloat())
            horizontalScroll.scrollTo(targetX.toInt())
        }
    }

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.cursor,
            backgroundColor = colors.selection,
        )
    ) {
        Surface(modifier = modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                ) {
                    Spacer(Modifier.width(gutterWidth))
                    Box(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .width(contentWidth)
                                .height(contentHeight)
                                .padding(top = topPadding, bottom = topPadding),
                            textStyle = codeStyle,
                            visualTransformation = if (highlightEnabled) transformation else VisualTransformation.None,
                            cursorBrush = SolidColor(colors.cursor),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                autoCorrect = false,
                                capitalization = KeyboardCapitalization.None,
                            ),
                            readOnly = readOnly,
                        )
                    }
                }

                // Line-number gutter: fixed on the left, synced to vertical scroll,
                // and only the visible numbers are drawn.
                Box(
                    Modifier
                        .width(gutterWidth)
                        .fillMaxHeight()
                        .drawBehind {
                            val lineH = lineHeight.toPx()
                            val padTop = topPadding.toPx()
                            val first = floor((verticalScroll.value - padTop) / lineH).toInt()
                                .coerceAtLeast(0)
                            val visible = (size.height / lineH).toInt() + 2
                            val last = minOf(first + visible, lineCount)
                            for (line in first until last) {
                                val text = "${line + 1}"
                                val layout = numberCache.getOrPut(line) {
                                    textMeasurer.measure(text, numberStyle)
                                }
                                drawText(
                                    textLayoutResult = layout,
                                    topLeft = Offset(
                                        size.width - layout.size.width - 6.dp.toPx(),
                                        line * lineH + padTop - verticalScroll.value
                                    )
                                )
                            }
                        }
                )
                // Hairline between gutter and code.
                Box(
                    Modifier
                        .padding(start = gutterWidth)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.gutterEdge)
                )
            }
        }
    }
}
