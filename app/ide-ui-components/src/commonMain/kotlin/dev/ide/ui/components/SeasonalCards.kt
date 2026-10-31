package dev.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** What the Halloween card on Home is saying. */
enum class HalloweenCardMode {
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
    mode: HalloweenCardMode,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(if (mode == HalloweenCardMode.SeasonOver) Res.string.halloween_over_title else Res.string.halloween_title)
    val body = stringResource(
        when (mode) {
            HalloweenCardMode.Offer -> Res.string.halloween_offer
            HalloweenCardMode.Applied -> Res.string.halloween_applied
            HalloweenCardMode.SeasonOver -> Res.string.halloween_over_body
        },
    )
    val primary = when (mode) {
        HalloweenCardMode.Offer -> stringResource(Res.string.halloween_dress_up)
        HalloweenCardMode.Applied -> null
        HalloweenCardMode.SeasonOver -> stringResource(Res.string.halloween_put_back)
    }
    val secondary = stringResource(
        when (mode) {
            HalloweenCardMode.Offer -> Res.string.halloween_not_now
            HalloweenCardMode.Applied -> Res.string.halloween_undo
            HalloweenCardMode.SeasonOver -> Res.string.halloween_keep
        },
    )
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(NightTop, NightBottom))),
    ) {
        // A bat in the top corner, purely for the silhouette.
        Canvas(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp).size(36.dp, 22.dp)) {
            drawBat(Offset(size.width / 2f, size.height / 2f), size.width, flap = 0.6f, color = Color(0xFF4A3A58))
        }
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Canvas(Modifier.size(56.dp)) {
                drawPumpkin(Offset(size.width / 2f, size.height * 0.56f), size.width * 0.92f)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = NightText)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = NightBody)
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (primary != null) {
                        Surface(onClick = onPrimary, shape = CircleShape, color = PumpkinOrange, contentColor = OnPumpkin, modifier = Modifier.height(36.dp)) {
                            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text(primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Surface(onClick = onSecondary, shape = CircleShape, color = Color.Transparent, contentColor = NightText, modifier = Modifier.height(36.dp)) {
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(secondary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

private val NightTop = Color(0xFF2E1C38)
private val NightBottom = Color(0xFF17101C)
private val NightText = Color(0xFFF3E6FF)
private val NightBody = Color(0xFFCDBFD8)
private val PumpkinOrange = Color(0xFFFF9A3C)
private val OnPumpkin = Color(0xFF2A1400)
