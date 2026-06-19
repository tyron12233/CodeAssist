package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * Single source of truth for the Beta program: the version label shown in-app and the destination the
 * "Submit suggestions" action opens. Change [FEEDBACK_URL] in one place when the feedback channel moves.
 */
object BetaInfo {
    /** Short label rendered in the Beta badge/banner. */
    const val LABEL: String = "Beta"

    /** Where "Submit suggestions" sends the user (opened via FileActions.openUrl). */
    const val FEEDBACK_URL: String = "https://github.com/tyron12233/CodeAssist/issues/new"

    /** What "Learn more" on the analytics consent prompt opens — the data-collection details. */
    const val PRIVACY_URL: String = "https://github.com/tyron12233/CodeAssist/blob/main/docs/analytics.md"
}

/**
 * A persistent "you're on Beta" banner for the Projects screen: an amber-tinted card explaining that
 * features may be incomplete, with a "Submit suggestions" action. [onSubmit] is wired by the host to
 * open [BetaInfo.FEEDBACK_URL]; pass null (or hide) when the host can't open external links.
 */
@Composable
fun BetaBanner(onSubmit: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ca.colors.warning.copy(alpha = 0.12f), shape)
            .border(1.dp, Ca.colors.warning.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CaIcons.info, null, Modifier.size(18.dp), tint = Ca.colors.warning)
            Text("You're on the ${BetaInfo.LABEL}", color = Ca.colors.textPrimary, style = Ca.type.headline)
        }
        Text(
            "Some features may be incomplete or change. We'd love your feedback — tell us what's missing or broken.",
            color = Ca.colors.textSecondary,
            style = Ca.type.footnote,
        )
        if (onSubmit != null) {
            Spacer(Modifier.height(0.dp))
            SubmitSuggestionsButton(onSubmit)
        }
    }
}

/** A compact pill button (amber outline) that opens the feedback channel. */
@Composable
private fun SubmitSuggestionsButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .background(Ca.colors.warning.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.pill))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.lightbulb, null, Modifier.size(16.dp), tint = Ca.colors.warning)
        Text("Submit suggestions", color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
    }
}

/** A small "Beta" badge for use beside titles/headers. */
@Composable
fun BetaBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Ca.colors.warning.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.pill))
            .border(1.dp, Ca.colors.warning.copy(alpha = 0.4f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(BetaInfo.LABEL.uppercase(), color = Ca.colors.warning, style = Ca.type.caption2, fontWeight = FontWeight.Bold)
    }
}
