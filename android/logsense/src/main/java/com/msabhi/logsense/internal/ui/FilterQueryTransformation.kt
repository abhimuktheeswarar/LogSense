package com.msabhi.logsense.internal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Colors the Android-Studio-style filter query as you type: `key:` prefixes in the accent color,
 * a leading `-` (negation) in the error color. Length is unchanged, so the offset mapping is identity.
 */
internal class FilterQueryTransformation(
    private val keyColor: Color,
    private val negateColor: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            var i = 0
            while (i < raw.length) {
                // skip leading whitespace
                while (i < raw.length && raw[i].isWhitespace()) i++
                val start = i
                while (i < raw.length && !raw[i].isWhitespace()) i++
                if (start >= i) continue
                val token = raw.substring(start, i)
                var p = 0
                if (token.startsWith('-') && token.length > 1) {
                    addStyle(SpanStyle(color = negateColor), start, start + 1)
                    p = 1
                }
                val colon = token.indexOf(':', p)
                if (colon > p) {
                    val key = token.substring(p, colon).lowercase()
                    if (key in KEYS) addStyle(SpanStyle(color = keyColor), start + p, start + colon + 1)
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }

    private companion object {
        val KEYS = setOf("tag", "message", "msg", "level", "package", "pkg")
    }
}
