package dev.ide.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.ad_label
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * A native-ad slot. Reads the active [dev.ide.ui.ads.AdController]; renders nothing when there's no controller
 * or ads aren't active (so call sites can drop it unconditionally). When active, the host paints the slot: the
 * ad inside the app's own card chrome ([NativeAdCard]) once one has loaded, and nothing at all before that or
 * when none fills, so an empty slot takes no space. There is no per-ad opt-out; ads are turned off from
 * Settings.
 */
@Composable
fun AdSlot(placement: AdPlacement, modifier: Modifier = Modifier) {
    val ads = LocalAds.current ?: return
    if (!ads.adsActive) return
    ads.host.NativeAd(placement, modifier)
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
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
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
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
