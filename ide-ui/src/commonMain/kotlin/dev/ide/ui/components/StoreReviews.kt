package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiStoreReview
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.reviews_helpful
import dev.ide.ui.generated.resources.reviews_helpful_count
import dev.ide.ui.generated.resources.reviews_on_version
import dev.ide.ui.generated.resources.reviews_reader
import dev.ide.ui.generated.resources.reviews_reply
import dev.ide.ui.generated.resources.reviews_yours
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource

/**
 * A row of five stars.
 *
 * [onSelect] makes it an input; null leaves it a read-out. The same component both ways so a rating never
 * looks different from the rating you are about to give.
 */
@Composable
fun StarRow(
    stars: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 16.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    onSelect: ((Int) -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (onSelect != null) 6.dp else 1.dp)) {
        repeat(5) { i ->
            val filled = i < stars
            Symbol(
                CaSymbols.star,
                contentDescription = null,
                size = size,
                filled = filled,
                tint = if (filled) tint else MaterialTheme.colorScheme.outlineVariant,
                modifier = if (onSelect != null) {
                    Modifier.clickable { onSelect(i + 1) }.padding(2.dp)
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * The star distribution as five bars.
 *
 * Proportional to the most common rating rather than to the total, because the shape of the distribution is
 * the point: whether the fours or the ones dominate is what a reader is looking for, and scaling by total
 * makes every bar short as soon as a project has a spread.
 */
@Composable
fun RatingHistogram(distribution: Map<Int, Int>, modifier: Modifier = Modifier) {
    val peak = (distribution.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (star in 5 downTo 1) {
            val n = distribution[star] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    star.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(10.dp),
                )
                Box(
                    Modifier.weight(1f).height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                ) {
                    if (n > 0) {
                        Box(
                            Modifier.fillMaxWidth(n.toFloat() / peak).height(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }
                Text(
                    n.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(28.dp),
                )
            }
        }
    }
}

/**
 * One review.
 *
 * The helpful button is the only control, and it is deliberately not shown on your own review: voting for
 * yourself is refused by the backend anyway, so offering the button would only invite the refusal.
 */
@Composable
fun ReviewCard(
    review: UiStoreReview,
    relativeTime: String?,
    onVote: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (review.mine) c.primaryContainer.copy(alpha = 0.45f) else c.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = c.secondaryContainer, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Symbol(
                            CaSymbols.person,
                            contentDescription = null,
                            size = 17.dp,
                            tint = c.onSecondaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            // A reviewer who has never published has no display name, and inventing one
                            // from an id would be worse than a neutral label.
                            if (review.mine) {
                                stringResource(Res.string.reviews_yours)
                            } else {
                                review.authorName ?: review.authorHandle ?: stringResource(Res.string.reviews_reader)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = c.onSurface,
                        )
                        if (review.verified) {
                            Symbol(CaSymbols.verified, contentDescription = null, size = 14.dp, tint = c.primary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StarRow(review.stars, size = 12.dp)
                        relativeTime?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = c.outline)
                        }
                        review.itemVersion?.let {
                            Text(
                                stringResource(Res.string.reviews_on_version, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = c.outline,
                            )
                        }
                    }
                }
            }
            review.review?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
            }
            review.reply?.takeIf { it.isNotBlank() }?.let { reply ->
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceContainerHighest) {
                    Column(Modifier.padding(12.dp)) {
                        Eyebrow(stringResource(Res.string.reviews_reply))
                        Spacer(Modifier.height(4.dp))
                        Text(reply, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
            if (onVote != null && !review.mine) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = { onVote(!review.votedByMe) },
                    shape = CircleShape,
                    color = if (review.votedByMe) c.primary else c.surfaceContainerHighest,
                    contentColor = if (review.votedByMe) c.onPrimary else c.onSurfaceVariant,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Symbol(CaSymbols.thumbUp, contentDescription = null, size = 15.dp, filled = review.votedByMe)
                        Text(
                            if (review.helpful > 0) {
                                stringResource(Res.string.reviews_helpful_count, review.helpful)
                            } else {
                                stringResource(Res.string.reviews_helpful)
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/** The big average, beside the histogram. */
@Composable
fun RatingSummary(average: Float, count: Int, distribution: Map<Int, Int>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (average >= 0f) formatStars(average) else "–",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 46.sp),
                fontWeight = FontWeight.Light,
                color = c.onSurface,
            )
            StarRow(average.toInt().coerceIn(0, 5), size = 13.dp, modifier = Modifier.padding(top = 2.dp))
            Text(
                if (count == 1) "1" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = c.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        RatingHistogram(distribution, Modifier.weight(1f))
    }
}

/** One decimal, always: "4.0" not "4", so the column of numbers lines up. */
internal fun formatStars(value: Float): String {
    val rounded = kotlin.math.round(value * 10).toInt()
    return "${rounded / 10}.${rounded % 10}"
}
