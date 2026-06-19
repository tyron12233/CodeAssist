package dev.ide.ui.editor.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * Shown inside the preview device frame when the renderer catches a top-level exception while
 * interpreting the `@Preview` composable. Replaces the preview content so the error is visible
 * without leaving an empty/blank frame that looks like a display glitch.
 *
 * The chip above the frame carries full detail; this view just confirms something went wrong and
 * shows the exception type + message so the developer can see it without tapping.
 */
@Composable
fun PreviewRenderError(error: Throwable, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            CaIcons.error,
            contentDescription = "render error",
            modifier = Modifier.size(20.dp),
            tint = Ca.colors.error,
        )
        Text(
            "Preview failed to render",
            color = Ca.colors.error,
            style = Ca.type.body,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        val detail = buildString {
            append(error::class.simpleName ?: "Error")
            val msg = error.message?.trim()?.let { if (it.length > 120) it.take(117) + "…" else it }
            if (!msg.isNullOrEmpty()) append(": $msg")
        }
        Text(
            detail,
            color = Ca.colors.textSecondary,
            style = Ca.type.caption,
            textAlign = TextAlign.Center,
        )
    }
}
