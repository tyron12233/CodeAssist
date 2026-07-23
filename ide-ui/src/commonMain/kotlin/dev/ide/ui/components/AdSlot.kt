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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.ad_label
import dev.ide.ui.generated.resources.house_ad_support_content
import dev.ide.ui.generated.resources.house_ad_support_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * A native-ad slot. Reads the active [dev.ide.ui.ads.AdController]; renders nothing when there's no controller
 * or ads aren't active (so call sites can drop it unconditionally). When active it wraps the host's native ad
 * in the app's own card chrome ([NativeAdCard]) so it reads as part of the UI. There is no per-ad opt-out — ads
 * are turned off from the quieter "Show ads" toggle on the picker's support card.
 */
@Composable
fun AdSlot(placement: AdPlacement, modifier: Modifier = Modifier) {
    val ads = LocalAds.current ?: return
    if (!ads.adsActive) return
    NativeAdCard(modifier) {
        ads.host.NativeAd(placement, Modifier.fillMaxWidth())
    }
}

/**
 * The shared chrome every native ad sits inside: the app's card surface + an "Ad" disclosure pill above the
 * host-supplied [body]. Keeping the chrome here (not in the host) is what makes the ad feel native — every
 * placement gets the same border, radius, and label.
 */
@Composable
fun NativeAdCard(
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AdBadge()
        body()
    }
}

/** A muted "Ad" pill marking sponsored content, per store/native-ad disclosure norms. */
@Composable
private fun AdBadge() {
    Text(
        stringResource(Res.string.ad_label),
        color = Ca.colors.textTertiary,
        style = Ca.type.caption2,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A house ad shown in an ad slot when no real ad network has been wired yet (Phase 1) — a gentle prompt to
 * support the project. Lives in the shared UI so the host can render it inside [AdHost.NativeAd] and so it can
 * be exercised in previews/tests; [onClick] opens the support/donation flow.
 */
@Composable
fun HouseAd(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(Ca.colors.accent.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.heart, null, Modifier.size(22.dp), tint = Ca.colors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.house_ad_support_title), color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(stringResource(Res.string.house_ad_support_content), color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
    }
}
