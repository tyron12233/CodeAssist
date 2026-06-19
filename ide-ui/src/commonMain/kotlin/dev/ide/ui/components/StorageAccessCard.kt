package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * A persistent "where your files live" panel for the Projects screen. Shows the on-disk app folder (the
 * whole CodeAssist directory: projects plus the SDK, keystore, and caches), tap to copy, explains that
 * it's reachable from any file manager, and offers a one-tap "Open in Files" — the in-app counterpart to
 * the on-device DocumentsProvider. Doubles as the first-run storage explainer: users coming from a version
 * that hid files in the sandbox can now find and share them. Renders nothing when there's no managed
 * storage root ([path] is null).
 *
 * [onOpenInFiles] is wired by the host to [dev.ide.ui.backend.FileActions.reveal]; pass null when the host
 * can't open a file manager (the button is then hidden, the path + copy still show).
 */
@Composable
fun StorageAccessCard(path: String?, onOpenInFiles: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (path.isNullOrBlank()) return
    val shape = RoundedCornerShape(Ca.radius.lg)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.sm)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.folder, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            }
            Column(Modifier.weight(1f)) {
                Text("Your CodeAssist files", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
                Text(
                    "Open this folder in any file manager to browse your projects (including any from older versions, under \"Projects\"), add icons or assets, or edit from a PC.",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption2,
                )
            }
        }
        // The path itself — tap to copy (handy for adb / a PC file manager).
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
                .clickable(interaction, indication = null) {
                    clipboard.setText(AnnotatedString(path)); copied = true
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                path,
                color = Ca.colors.textSecondary,
                style = Ca.type.caption,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(if (copied) CaIcons.check else CaIcons.copy, "Copy path", Modifier.size(15.dp), tint = Ca.colors.textTertiary)
        }
        if (onOpenInFiles != null) {
            val openInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressScale(openInteraction)
                    .background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.pill))
                    .clickable(openInteraction, indication = null, onClick = onOpenInFiles)
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(CaIcons.share, null, Modifier.size(16.dp), tint = Ca.colors.accent)
                Box(Modifier.size(6.dp))
                Text("Open in file manager", color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
