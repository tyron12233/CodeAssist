package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.comingsoon_footnote
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * A placeholder for an unfinished destination: a soft accent-tinted icon medallion, a title, a one-line
 * description, and an optional "planned" footnote. Reused by any surface that isn't implemented yet
 * (Source control, etc.).
 */
@Composable
fun ComingSoon(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    footnote: String? = stringResource(Res.string.comingsoon_footnote),
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(24.dp).entranceSlideUp(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(64.dp).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.lg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(30.dp), tint = Ca.colors.accent)
            }
            Text(
                title, color = Ca.colors.textPrimary, style = Ca.type.title3,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Text(
                description, color = Ca.colors.textSecondary, style = Ca.type.footnote,
                textAlign = TextAlign.Center,
            )
            if (footnote != null) {
                Chip(footnote, fill = Ca.colors.surface2, textColor = Ca.colors.textTertiary)
            }
        }
    }
}
