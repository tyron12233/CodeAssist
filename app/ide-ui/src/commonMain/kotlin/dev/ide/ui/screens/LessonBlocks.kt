package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiContentBlock
import dev.ide.ui.components.CodeSample
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.highlight
import dev.ide.ui.editor.preview.LessonComposePreview
import dev.ide.ui.editor.preview.LessonLayoutPreview
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.markdown.MdStyles
import dev.ide.ui.markdown.buildInline
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
                    Text(inlineMarkup(block.md), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
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



@Composable
private fun Callout(kind: String, text: String) {
    val (icon, tint) = when (kind) {
        "warn" -> CaIcons.warning to Ide.colors.warning
        "note" -> CaIcons.info to Ide.colors.info
        else -> CaIcons.lightbulb to MaterialTheme.colorScheme.primary
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
        Text(inlineMarkup(text), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Render inline lesson markup — `**bold**`, `*italic*`, `` `code` ``, links — into an [AnnotatedString],
 * via the shared Markdown inline scanner ([buildInline]). Base spans carry no color so the caller's `Text`
 * color applies; only code + links are tinted.
 */
@Composable
fun inlineMarkup(md: String): AnnotatedString {
    val codeFamily = Ide.type.codeFamily
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(md, codeFamily, codeBg, codeColor, linkColor) {
        buildInline(
            md,
            MdStyles(
                base = SpanStyle(),
                code = SpanStyle(fontFamily = codeFamily, background = codeBg, color = codeColor),
                link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
        )
    }
}
