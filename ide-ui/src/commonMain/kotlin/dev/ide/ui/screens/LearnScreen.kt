package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.Chip
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The home screen's Learn tab: jumping-off points to the docs, the community, and the store's samples, plus
 * a placeholder for the in-app tutorials still to come. A null callback hides its card (e.g. the host can't
 * open links), so the tab degrades gracefully.
 */
@Composable
fun LearnScreen(
    modifier: Modifier = Modifier,
    onOpenDocs: (() -> Unit)? = null,
    onJoinDiscord: (() -> Unit)? = null,
    onBrowseSamples: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Learn", color = Ca.colors.textPrimary, style = Ca.type.large, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Guides, samples, and the community", color = Ca.colors.textSecondary, style = Ca.type.subhead)
            }
            Spacer(Modifier.height(6.dp))

            var i = 0
            if (onOpenDocs != null) {
                LearnCard(CaIcons.docText, "Documentation", "Architecture, build, and language guides.", delayMillis = i++ * 50, onClick = onOpenDocs)
            }
            if (onBrowseSamples != null) {
                LearnCard(CaIcons.grid, "Browse samples", "Open the store's sample projects to read and run.", delayMillis = i++ * 50, onClick = onBrowseSamples)
            }
            if (onJoinDiscord != null) {
                LearnCard(CaIcons.discord, "Join the community", "Ask questions and share what you build.", delayMillis = i++ * 50, accent = DiscordBlurple, onClick = onJoinDiscord)
            }
            // The in-app, interactive tutorials are not built yet; surface the destination without a fake link.
            SoonCard(CaIcons.lightbulb, "Interactive tutorials", "Step-by-step lessons inside the editor.")
        }
    }
}

private val DiscordBlurple = Color(0xFF5865F2)

@Composable
private fun LearnCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    delayMillis: Int,
    onClick: () -> Unit,
    accent: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    val tint = accent ?: Ca.colors.accent
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(subtitle, color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
    }
}

@Composable
private fun SoonCard(icon: ImageVector, title: String, subtitle: String) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = Ca.colors.textTertiary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textSecondary, style = Ca.type.headline)
            Text(subtitle, color = Ca.colors.textTertiary, style = Ca.type.footnote)
        }
        Chip("Soon", fill = Ca.colors.surface2, textColor = Ca.colors.textTertiary)
    }
}
