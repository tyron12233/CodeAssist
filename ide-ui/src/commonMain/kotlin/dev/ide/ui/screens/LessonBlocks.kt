package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiContentBlock
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.highlight
import dev.ide.ui.editor.preview.LessonComposePreview
import dev.ide.ui.editor.preview.LessonLayoutPreview
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/** Render a lesson's content blocks (explanation text with tiny inline markup, read-only code samples,
 *  highlighted callouts, and live layout/Compose previews). Shared by the concept and interactive steps of the
 *  lesson player. [backend] powers [UiContentBlock.LayoutPreview]/[UiContentBlock.ComposePreview] blocks (when
 *  null they degrade to a read-only source sample); [host] is the platform Compose renderer a
 *  [UiContentBlock.ComposePreview] needs (null → read-only Kotlin sample). */
@Composable
fun LessonBlocks(
    blocks: List<UiContentBlock>,
    modifier: Modifier = Modifier,
    backend: IdeBackend? = null,
    host: ComposePreviewHost? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is UiContentBlock.Text ->
                    Text(inlineMarkup(block.md), color = Ca.colors.textSecondary, style = Ca.type.subhead)
                is UiContentBlock.Code -> CodeSample(block.code, block.language)
                is UiContentBlock.Callout -> Callout(block.kind, block.text)
                is UiContentBlock.LayoutPreview ->
                    if (backend != null) {
                        LessonLayoutPreview(
                            xml = block.xml,
                            backend = backend,
                            interactive = block.interactive,
                            caption = block.caption,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        CodeSample(block.xml.trim(), "xml")
                    }
                is UiContentBlock.ComposePreview ->
                    if (backend != null && host != null) {
                        LessonComposePreview(
                            code = block.code,
                            backend = backend,
                            host = host,
                            interactive = block.interactive,
                            caption = block.caption,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        CodeSample(block.code.trim(), "kotlin")
                    }
            }
        }
    }
}

/** A read-only, syntax-highlighted code sample on a soft editor-toned card; scrolls horizontally if wide. */
@Composable
fun CodeSample(code: String, language: String = "kotlin", modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Ca.radius.md)
    val highlighted = highlight(code, codeLanguageOf(language), Ca.colors.syntax)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ca.colors.editorBg)
            .border(1.dp, Ca.colors.hairline, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(highlighted, style = Ca.type.code)
    }
}

/** Map a lesson block's language string to the editor's [CodeLanguage] for highlighting. */
fun codeLanguageOf(language: String): CodeLanguage = when {
    language.startsWith("java") -> CodeLanguage.Java
    language.startsWith("kotlin") || language == "kt" -> CodeLanguage.Kotlin
    language == "xml" -> CodeLanguage.Xml
    else -> CodeLanguage.Plain
}

@Composable
private fun Callout(kind: String, text: String) {
    val (icon, tint) = when (kind) {
        "warn" -> CaIcons.warning to Ca.colors.warning
        "note" -> CaIcons.info to Ca.colors.info
        else -> CaIcons.lightbulb to Ca.colors.accent
    }
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.25f), shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(inlineMarkup(text), color = Ca.colors.textSecondary, style = Ca.type.footnote)
    }
}

/** Parse the tiny lesson markup — `**bold**` and `` `code` `` — into an [AnnotatedString]. */
@Composable
fun inlineMarkup(md: String): AnnotatedString {
    val codeFamily = Ca.type.codeFamily
    val codeBg = Ca.colors.surface2
    val codeColor = Ca.colors.textPrimary
    return remember(md, codeFamily, codeBg, codeColor) {
        buildAnnotatedString {
            var i = 0
            while (i < md.length) {
                when {
                    md.startsWith("**", i) -> {
                        val end = md.indexOf("**", i + 2)
                        if (end < 0) { append(md.substring(i)); i = md.length }
                        else {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = codeColor)) { append(md.substring(i + 2, end)) }
                            i = end + 2
                        }
                    }
                    md[i] == '`' -> {
                        val end = md.indexOf('`', i + 1)
                        if (end < 0) { append(md.substring(i)); i = md.length }
                        else {
                            withStyle(SpanStyle(fontFamily = codeFamily, background = codeBg, color = codeColor)) { append(md.substring(i + 1, end)) }
                            i = end + 1
                        }
                    }
                    else -> { append(md[i]); i++ }
                }
            }
        }
    }
}
