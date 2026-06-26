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
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 24.dp, vertical = 8.dp),
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
                    .background(Ca.colors.glassThick, shape)
                    .border(1.dp, Ca.colors.glassEdge, shape)
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
            Modifier.size(72.dp).background(Ca.colors.accent.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.info, null, Modifier.size(34.dp), tint = Ca.colors.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(Res.string.help_improve_codeassist), color = Ca.colors.textPrimary, style = Ca.type.title2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(Res.string.help_improve_codeassist_content),
            color = Ca.colors.textSecondary,
            style = Ca.type.subhead,
            textAlign = TextAlign.Center,
        )
        if (onLearnMore != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.learn_more),
                color = Ca.colors.accent,
                style = Ca.type.subhead,
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onLearnMore)
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = stringResource(Res.string.allow), onClick = onAllow, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.no_thanks),
            color = Ca.colors.textSecondary,
            style = Ca.type.subhead,
            modifier = Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDecline)
                .padding(12.dp),
        )
    }
}

/** A compact reusable analytics on/off row for a settings surface (the editor's More menu). */
@Composable
fun AnalyticsToggleRow(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null) { onChange(!enabled) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CaIcons.info, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
        Column(Modifier.weight(1f)) {
            Text("Performance analytics", color = Ca.colors.textPrimary, style = Ca.type.subhead)
            Text(
                if (enabled) "Sharing anonymous performance data" else "Not sharing performance data",
                color = Ca.colors.textSecondary,
                style = Ca.type.caption,
            )
        }
        ConsentToggle(enabled, onChange)
    }
}

/** A small on-brand pill toggle (matches the module-settings switch), local to the analytics row. */
@Composable
private fun ConsentToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(if (on) Ca.colors.accent else Ca.colors.surface3, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onToggle(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(Ca.colors.textOnAccent, RoundedCornerShape(Ca.radius.pill)))
    }
}
