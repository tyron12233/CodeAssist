package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.platform.LocalWindowTitleBar

/** Height of the shell's plain title strip, IntelliJ's 40px title bar. */
val WindowTitleStripHeight = 40.dp

/**
 * The shell's plain title strip: drawn where the app owns the window's title bar (see
 * [dev.ide.ui.platform.WindowTitleBar]) but the current screen draws no bar into it, so the window always has
 * a centred [title] and a place to drag it by. Renders nothing where the system draws its own title bar, or
 * while a screen (the editor's top bar) has claimed the title bar.
 */
@Composable
fun WindowTitleStrip(title: String) {
    val bar = LocalWindowTitleBar.current ?: return
    if (bar.claimed) return
    Column(Modifier.fillMaxWidth()) {
        Box(
            with(bar) {
                Modifier.fillMaxWidth().height(bar.height)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .titleBarArea()
                    .padding(start = bar.startInset + 8.dp, end = bar.endInset + 8.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
