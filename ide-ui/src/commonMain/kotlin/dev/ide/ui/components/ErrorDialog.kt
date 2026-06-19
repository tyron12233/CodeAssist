package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiError
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The IntelliJ-style non-fatal error dialog. When the engine logs an unexpected error (or an uncaught
 * exception is intercepted), the app keeps running and this overlays a dismissible report: a one-line
 * summary plus an expandable, selectable stack trace. Observes [IdeBackend.errorEvents] and clears it via
 * [IdeBackend.dismissError]. Hosted app-wide so it appears over any screen.
 */
@Composable
fun ErrorDialog(backend: IdeBackend) {
    val error by backend.errorEvents.collectAsState()
    // Retain the last error so the exit animation doesn't flash empty as it clears.
    var shown by remember { mutableStateOf<UiError?>(null) }
    if (error != null) shown = error

    fun dismiss() { shown?.let { backend.dismissError(it.id) } }

    CenteredDialog(visible = error != null, onDismiss = { dismiss() }) {
        shown?.let { err -> ErrorCard(err, onDismiss = ::dismiss) }
    }
}

@Composable
private fun ErrorCard(err: UiError, onDismiss: () -> Unit) {
    var expanded by remember(err.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Ca.radius.xl)
    Column(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Ca.colors.glassThick, shape)
            .border(1.dp, Ca.colors.glassEdge, shape)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(Ca.colors.error.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(CaIcons.error, null, Modifier.size(22.dp), tint = Ca.colors.error) }
            Column(Modifier.weight(1f)) {
                Text(err.title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
                if (err.timeLabel.isNotEmpty()) {
                    Text(err.timeLabel, color = Ca.colors.textTertiary, style = Ca.type.caption2)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            err.message.ifBlank { "An unexpected error occurred. The app has kept running." },
            color = Ca.colors.textSecondary,
            style = Ca.type.subhead,
        )

        if (err.detail.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (expanded) "Hide details" else "Show details",
                color = Ca.colors.accent,
                style = Ca.type.caption,
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md))
                            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.md))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                err.detail,
                                color = Ca.colors.textSecondary,
                                style = Ca.type.caption2.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "Dismiss", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
