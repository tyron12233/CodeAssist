package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiLearnBadge
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.learn_badges_none
import dev.ide.ui.generated.resources.learn_best_streak
import dev.ide.ui.generated.resources.learn_lessons_label
import dev.ide.ui.generated.resources.learn_streak_label
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource

/**
 * A profile's Learning section: the day streak, a lesson count, and a badge for each finished track. Used by
 * the You screen (the learner's own) and the public publisher profile, so both read the same way.
 *
 * [showEmptyBadges] is for the learner's own page, where "finish a track to earn its badge" is a nudge; a
 * stranger's empty badge shelf is simply left out.
 */
@Composable
internal fun LearnProfileCard(
    streak: Int,
    bestStreak: Int,
    lessons: Int,
    badges: List<UiLearnBadge>,
    modifier: Modifier = Modifier,
    showEmptyBadges: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Figure(
                    glyph = CaSymbols.localFireDepartment,
                    value = streak.toString(),
                    label = stringResource(Res.string.learn_streak_label),
                    sub = if (bestStreak > streak) stringResource(Res.string.learn_best_streak, bestStreak) else null,
                    container = c.tertiaryContainer,
                    content = c.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Figure(
                    glyph = CaSymbols.school,
                    value = lessons.toString(),
                    label = stringResource(Res.string.learn_lessons_label),
                    sub = null,
                    container = c.secondaryContainer,
                    content = c.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            if (badges.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    badges.forEach { BadgeChip(it) }
                }
            } else if (showEmptyBadges) {
                Text(
                    stringResource(Res.string.learn_badges_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Figure(
    glyph: Char,
    value: String,
    label: String,
    sub: String?,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(container).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 20.dp, tint = content)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = content, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        Text(
            sub.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.75f),
            modifier = Modifier.height(16.dp),
        )
    }
}

@Composable
private fun BadgeChip(badge: UiLearnBadge) {
    val c = MaterialTheme.colorScheme
    val accent = badge.accentColor?.let { Color(it) } ?: c.primary
    Row(
        Modifier.clip(CircleShape).background(accent.copy(alpha = 0.14f)).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Symbol(CaSymbols.workspacePremium, contentDescription = null, size = 15.dp, tint = Color.White)
        }
        Text(
            badge.title,
            style = MaterialTheme.typography.labelLarge,
            color = c.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
