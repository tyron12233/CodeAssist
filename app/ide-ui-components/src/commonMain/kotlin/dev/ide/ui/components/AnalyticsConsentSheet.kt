package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.allow
import dev.ide.ui.generated.resources.help_improve_codeassist
import dev.ide.ui.generated.resources.help_improve_codeassist_content
import dev.ide.ui.generated.resources.learn_more
import dev.ide.ui.generated.resources.no_thanks
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The one-time, **opt-in** analytics consent prompt shown on first launch (after onboarding). Collection
 * does not begin until the user taps "Allow" — declining (or dismissing) records the decision so the
 * prompt isn't shown again. Plain-language summary of what's collected and the firm "never" line; an
 * optional [onLearnMore] opens the privacy details. Adapts to the platform like the other notices
 * (centered dialog on desktop, bottom sheet on mobile).
 *
 * [onAllow]/[onDecline] persist the decision (the host writes the consent preference + toggles collection).
 */
@Composable
fun AnalyticsConsentSheet(
    visible: Boolean,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    onLearnMore: (() -> Unit)? = null,
) {
    if (isMobilePlatform) {
        BottomSheet(visible = visible, onDismiss = onDecline, heightFraction = 0.62f) {
            ConsentBody(
                onAllow = onAllow,
                onDecline = onDecline,
                onLearnMore = onLearnMore,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    } else {
        CenteredDialog(visible = visible, onDismiss = onDecline) {
            val shape = RoundedCornerShape(Ca.radius.sheet)
            ConsentBody(
                onAllow = onAllow,
                onDecline = onDecline,
                onLearnMore = onLearnMore,
                modifier = Modifier
                    .width(460.dp)
                    .background(Ide.colors.glassThick, shape)
                    .border(1.dp, Ide.colors.glassEdge, shape)
                    .padding(28.dp),
            )
        }
    }
}

@Composable
private fun ConsentBody(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    onLearnMore: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.info, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(Res.string.help_improve_codeassist),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(Res.string.help_improve_codeassist_content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (onLearnMore != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.learn_more),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLearnMore
                    )
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = stringResource(Res.string.allow),
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.no_thanks),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable(
                    remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDecline
                )
                .padding(12.dp),
        )
    }
}
