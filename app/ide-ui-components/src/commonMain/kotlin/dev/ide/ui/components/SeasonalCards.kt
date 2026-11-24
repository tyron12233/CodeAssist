package dev.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.winter_applied
import dev.ide.ui.generated.resources.winter_offer
import dev.ide.ui.generated.resources.winter_over_title
import dev.ide.ui.generated.resources.winter_title
import dev.ide.ui.generated.resources.halloween_applied
import dev.ide.ui.generated.resources.halloween_dress_up
import dev.ide.ui.generated.resources.halloween_keep
import dev.ide.ui.generated.resources.halloween_not_now
import dev.ide.ui.generated.resources.halloween_offer
import dev.ide.ui.generated.resources.halloween_over_body
import dev.ide.ui.generated.resources.halloween_over_title
import dev.ide.ui.generated.resources.halloween_put_back
import dev.ide.ui.generated.resources.halloween_title
import dev.ide.ui.generated.resources.halloween_undo
import org.jetbrains.compose.resources.stringResource

/** What a seasonal card on Home is saying. */
enum class SeasonalCardMode {
    /** In season, not yet answered: offer to dress the IDE up. */
    Offer,
    /** Just dressed up, this visit: confirm it and offer Undo. */
    Applied,
    /** The season has ended and the IDE is still dressed up: offer to put things back. */
    SeasonOver,
}

/**
 * The seasonal card on Home. It always draws on its own night palette rather than the theme's, because the
 * card is the one place the season should show before the user has opted into anything, and a pumpkin on a
 * lime card reads as a sticker rather than a night out.
 */
@Composable
fun HalloweenCard(
    mode: SeasonalCardMode,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(if (mode == SeasonalCardMode.SeasonOver) Res.string.halloween_over_title else Res.string.halloween_title)
    val body = stringResource(
        when (mode) {
            SeasonalCardMode.Offer -> Res.string.halloween_offer
            SeasonalCardMode.Applied -> Res.string.halloween_applied
            SeasonalCardMode.SeasonOver -> Res.string.halloween_over_body
        },
    )
    val primary = when (mode) {
        SeasonalCardMode.Offer -> stringResource(Res.string.halloween_dress_up)
        SeasonalCardMode.Applied -> null
        SeasonalCardMode.SeasonOver -> stringResource(Res.string.halloween_put_back)
    }
    val secondary = stringResource(
        when (mode) {
            SeasonalCardMode.Offer -> Res.string.halloween_not_now
            SeasonalCardMode.Applied -> Res.string.halloween_undo
            SeasonalCardMode.SeasonOver -> Res.string.halloween_keep
        },
    )
    SeasonCardScaffold(
        title = title,
        body = body,
        primary = primary,
        secondary = secondary,
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        palette = HalloweenPalette,
        modifier = modifier,
        corner = {
            // A bat in the top corner, purely for the silhouette.
            Canvas(Modifier.size(36.dp, 22.dp)) {
                drawBat(Offset(size.width / 2f, size.height / 2f), size.width, flap = 0.6f, color = Color(0xFF4A3A58))
            }
        },
        art = {
            Canvas(Modifier.size(56.dp)) {
                drawPumpkin(Offset(size.width / 2f, size.height * 0.56f), size.width * 0.92f)
            }
        },
    )
}

/**
 * The winter card on Home: the same three modes as [HalloweenCard], on a winter night of its own, with the
 * northern lights over snowy hills and a big snowflake.
 */
@Composable
fun WinterCard(
    mode: SeasonalCardMode,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(if (mode == SeasonalCardMode.SeasonOver) Res.string.winter_over_title else Res.string.winter_title)
    val body = stringResource(
        when (mode) {
            SeasonalCardMode.Offer -> Res.string.winter_offer
            SeasonalCardMode.Applied -> Res.string.winter_applied
            SeasonalCardMode.SeasonOver -> Res.string.halloween_over_body
        },
    )
    val primary = when (mode) {
        SeasonalCardMode.Offer -> stringResource(Res.string.halloween_dress_up)
        SeasonalCardMode.Applied -> null
        SeasonalCardMode.SeasonOver -> stringResource(Res.string.halloween_put_back)
    }
    val secondary = stringResource(
        when (mode) {
            SeasonalCardMode.Offer -> Res.string.halloween_not_now
            SeasonalCardMode.Applied -> Res.string.halloween_undo
            SeasonalCardMode.SeasonOver -> Res.string.halloween_keep
        },
    )
    SeasonCardScaffold(
        title = title,
        body = body,
        primary = primary,
        secondary = secondary,
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        palette = WinterPalette,
        modifier = modifier,
        corner = {
            Canvas(Modifier.size(40.dp, 28.dp)) {
                drawSkyStar(Offset(size.width * 0.72f, size.height * 0.38f), size.height * 0.3f, StarGold.copy(alpha = 0.8f))
                drawSkyStar(Offset(size.width * 0.25f, size.height * 0.72f), size.height * 0.18f, WinterSnow.copy(alpha = 0.6f))
            }
        },
        background = {
            // Snow on the ground along the bottom edge, behind the text.
            Canvas(Modifier.fillMaxSize()) {
                drawAurora(top = 0f, height = size.height * 0.55f, phase = 0.6f, strength = 0.9f)
                drawSnowHills(rise = 0.22f, color = Color(0xFF2A3550), back = Color(0xFF212B44))
            }
        },
        art = {
            Canvas(Modifier.size(56.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                drawRadialGlow(listOf(AuroraTeal.copy(alpha = 0.28f), AuroraTeal.copy(alpha = 0f)), c, size.minDimension * 0.55f)
                drawSnowflake(c, size.minDimension * 0.42f, WinterSnow, 8f)
            }
        },
    )
}

/** The fixed colors a seasonal card draws on, independent of the theme. */
private class SeasonCardPalette(
    val top: Color,
    val bottom: Color,
    val text: Color,
    val body: Color,
    val button: Color,
    val onButton: Color,
)

private val HalloweenPalette = SeasonCardPalette(
    top = Color(0xFF2E1C38), bottom = Color(0xFF17101C),
    text = Color(0xFFF3E6FF), body = Color(0xFFCDBFD8),
    button = Color(0xFFFF9A3C), onButton = Color(0xFF2A1400),
)

private val WinterPalette = SeasonCardPalette(
    top = WinterNavyTop, bottom = WinterNavy,
    text = Color(0xFFF2F4FA), body = Color(0xFFC3CADF),
    button = AuroraTeal, onButton = Color(0xFF00382F),
)

@Composable
private fun SeasonCardScaffold(
    title: String,
    body: String,
    primary: String?,
    secondary: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    palette: SeasonCardPalette,
    modifier: Modifier,
    corner: @Composable () -> Unit,
    art: @Composable () -> Unit,
    background: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(palette.top, palette.bottom))),
    ) {
        if (background != null) Box(Modifier.matchParentSize()) { background() }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp)) { corner() }
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            art()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = palette.body)
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (primary != null) {
                        Surface(onClick = onPrimary, shape = CircleShape, color = palette.button, contentColor = palette.onButton, modifier = Modifier.height(36.dp)) {
                            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text(primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Surface(onClick = onSecondary, shape = CircleShape, color = Color.Transparent, contentColor = palette.text, modifier = Modifier.height(36.dp)) {
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(secondary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
