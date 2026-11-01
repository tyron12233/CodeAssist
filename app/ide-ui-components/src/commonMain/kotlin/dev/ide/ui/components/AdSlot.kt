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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.ads.buildBannerDelayMs
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.ad_label
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
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
 * The height a pane needs before it carries a footer ad ([AdSlot] pinned under its content). The ad card takes
 * ~180dp, so in a shorter pane (any pane of a phone in landscape, a pane dragged small) it would leave the
 * content itself almost no room; there the footer stays empty.
 */
val AdFooterMinPaneHeight = 520.dp

/** A footer [AdSlot] for a pane [paneHeight] tall: nothing when the pane is shorter than [AdFooterMinPaneHeight]. */
@Composable
fun FooterAdSlot(placement: AdPlacement, paneHeight: Dp, modifier: Modifier = Modifier) {
    if (paneHeight < AdFooterMinPaneHeight) return
    AdSlot(placement, modifier)
}

/** How long a build runs before the console footer shows a banner, so a quick incremental build never flashes one. */
const val BUILD_BANNER_DELAY_MS = 3_000L

/** The console height a running build's footer banner needs: the banner and its card take about 110dp. */
val BuildBannerMinPaneHeight = 360.dp

/**
 * The build console footer's banner while a build runs ([AdHost.BannerAd]). The console only draws its native
 * footer ([FooterAdSlot]) between builds; during one, people watch the log without tapping, so a banner there
 * gets seen without inviting stray clicks. Compose it only while the build runs: it appears after
 * [BUILD_BANNER_DELAY_MS] (or once the full-screen build ad has had its turn, when one is due:
 * [buildBannerDelayMs]), never while the full-screen build ad is up (it would sit unseen behind it), and not on
 * low-memory devices, where the build interstitial is skipped for the same reason (an ad WebView next to an
 * on-device build). Renders nothing unless ads are active.
 */
@Composable
fun BuildRunningBannerSlot(paneHeight: Dp, modifier: Modifier = Modifier) {
    val ads = LocalAds.current ?: return
    if (!ads.adsActive || paneHeight < BuildBannerMinPaneHeight) return
    var due by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(buildBannerDelayMs(ads, BUILD_BANNER_DELAY_MS))
        due = true
    }
    if (!due || ads.host.lowMemoryDevice || ads.host.interstitialShowing) return
    ads.host.BannerAd(AdPlacement.BUILD_CONSOLE, modifier)
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
