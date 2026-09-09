package dev.ide.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Tonal container rotation and asymmetric corner cycling — the two devices the Home / Explore / Learn
 * design uses instead of a wall of identical white cards.
 *
 * **Rotation.** Every repeated tonal surface cycles through the three container roles by index:
 * primary → secondary → tertiary. A list of five project cards therefore carries three different tints,
 * which is what stops a scrolling list from reading as one grey slab. It applies to featured hero cards,
 * category tiles, create-project tiles, module icon tiles, publisher stat tiles, the trending ticker and
 * project icon tiles.
 *
 * **Never invent a color.** Text on a container always uses that container's matching `on*` role, which
 * is exactly what [tonalPair] returns — the pair, not the background alone, so a call site cannot pick
 * one and forget the other. The only sanctioned opacity steps are 0.70 (supporting text on a container),
 * 0.13 (watermark glyphs) and 0.50 (the progress underline on a filled button).
 */
@Immutable
data class TonalPair(val container: Color, val onContainer: Color)

/**
 * The container/on-container pair for position [index] in a repeated run. Cycles primary → secondary →
 * tertiary.
 */
@Composable
@ReadOnlyComposable
fun tonalPair(index: Int): TonalPair {
    val c = MaterialTheme.colorScheme
    return when (index.mod(3)) {
        0 -> TonalPair(c.primaryContainer, c.onPrimaryContainer)
        1 -> TonalPair(c.secondaryContainer, c.onSecondaryContainer)
        else -> TonalPair(c.tertiaryContainer, c.onTertiaryContainer)
    }
}

/**
 * Whether the asymmetric shape cycling is on.
 *
 * `true` (the default) is the expressive design: repeated cards rotate which corner is clipped, so a
 * list has a visible rhythm. `false` is the uniform variant — every card a plain 20 dp radius — kept as
 * a single switch because it is the conservative fallback if the cycling ever reads as noise at scale.
 */
val LocalExpressiveShapeCycling = staticCompositionLocalOf { true }

private val UniformCard = RoundedCornerShape(20.dp)
private val UniformTile = RoundedCornerShape(16.dp)

/**
 * The card silhouette for position [index] in a repeated run.
 *
 * Three of every four cards clip exactly one corner to 10 dp against 26 dp elsewhere, and which corner
 * moves down the list. One clipped corner reads as intentional; clipping two would read as a mistake.
 */
@Composable
@ReadOnlyComposable
fun cardShape(index: Int): Shape {
    if (!LocalExpressiveShapeCycling.current) return UniformCard
    return when (index.mod(4)) {
        0 -> RoundedCornerShape(26.dp)
        1 -> RoundedCornerShape(topStart = 26.dp, topEnd = 10.dp, bottomEnd = 26.dp, bottomStart = 26.dp)
        2 -> RoundedCornerShape(topStart = 10.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp)
        else -> RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 10.dp, bottomStart = 26.dp)
    }
}

/**
 * The icon-tile silhouette for position [index]: rounded square, circle, a diagonal pair, then a tighter
 * square. Runs on a 4-cycle against [cardShape]'s 4-cycle so a card and the tile inside it stay visually
 * distinct rather than echoing the same corner.
 */
@Composable
@ReadOnlyComposable
fun tileShape(index: Int): Shape {
    if (!LocalExpressiveShapeCycling.current) return UniformTile
    return when (index.mod(4)) {
        0 -> RoundedCornerShape(16.dp)
        1 -> CircleShape
        2 -> RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
        else -> RoundedCornerShape(14.dp)
    }
}

/** Fixed silhouettes the design pins by role rather than by list position. */
object CaShapes {
    /** Home's primary CTA ("New project"), 64 dp tall. */
    val PrimaryAction = RoundedCornerShape(topStart = 26.dp, topEnd = 12.dp, bottomEnd = 26.dp, bottomStart = 12.dp)
    /** The square button beside it (clone from Git) — deliberately the mirror of [PrimaryAction]. */
    val SquareAction = RoundedCornerShape(topStart = 12.dp, topEnd = 26.dp, bottomEnd = 12.dp, bottomStart = 26.dp)
    /** Detail install button, idle. Morphs to [InstallBusy] then [InstallDone] as the state machine runs. */
    val InstallIdle = RoundedCornerShape(topStart = 18.dp, topEnd = 28.dp, bottomEnd = 18.dp, bottomStart = 28.dp)
    val InstallBusy = RoundedCornerShape(28.dp)
    val InstallDone = RoundedCornerShape(topStart = 28.dp, topEnd = 18.dp, bottomEnd = 28.dp, bottomStart = 18.dp)
    /** Bottom sheets. */
    val Sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    /** Learn's "Continue" card. */
    val Continue = RoundedCornerShape(topStart = 32.dp, topEnd = 12.dp, bottomEnd = 32.dp, bottomStart = 32.dp)
    /** A developer's reply under a review: the notched corner points back at the review above it. */
    val Reply = RoundedCornerShape(topStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    /** Explore's featured hero card. */
    val Hero = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp)
    /** The snackbar. */
    val Snackbar = RoundedCornerShape(16.dp)
}
