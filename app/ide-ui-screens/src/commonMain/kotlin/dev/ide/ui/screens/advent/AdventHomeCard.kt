package dev.ide.ui.screens.advent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.ChristmasGold
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.advent_card_done
import dev.ide.ui.generated.resources.advent_card_open
import dev.ide.ui.generated.resources.advent_card_ready
import dev.ide.ui.generated.resources.advent_card_title
import dev.ide.ui.generated.resources.advent_card_tomorrow
import dev.ide.ui.generated.resources.advent_card_waiting
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.holiday.Ornament
import org.jetbrains.compose.resources.stringResource

/** What the Home card says about the calendar, worked out from the ledger. */
sealed interface AdventCardStatus {
    /** Before December 1. */
    data object Waiting : AdventCardStatus
    /** The earliest door whose day has come and that is still closed. */
    data class Ready(val door: Int) : AdventCardStatus
    /** Every door so far is open; [door] opens tomorrow. */
    data class Tomorrow(val door: Int) : AdventCardStatus
    /** All 25 are open. */
    data object Done : AdventCardStatus

    companion object {
        fun of(ledger: HolidayLedger): AdventCardStatus {
            val through = ledger.unlockedThrough()
            if (through == 0) return Waiting
            val opened = ledger.opened()
            val next = (1..through).firstOrNull { it !in opened }
            return when {
                next != null -> Ready(next)
                through < HolidayLedger.DOORS -> Tomorrow(through + 1)
                else -> Done
            }
        }
    }
}

/**
 * The calendar's way in from Home: the tree as it stands, and whether a door is waiting. The whole card opens
 * the calendar; a ready door makes it glow a little, the only nudge it gives.
 */
@Composable
fun AdventHomeCard(
    status: AdventCardStatus,
    ornaments: List<Ornament>,
    starLit: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = status is AdventCardStatus.Ready
    val line = when (status) {
        AdventCardStatus.Waiting -> stringResource(Res.string.advent_card_waiting)
        is AdventCardStatus.Ready -> stringResource(Res.string.advent_card_ready, status.door)
        is AdventCardStatus.Tomorrow -> stringResource(Res.string.advent_card_tomorrow, status.door)
        AdventCardStatus.Done -> stringResource(Res.string.advent_card_done)
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1C3A2D), Color(0xFF10201A))))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OrnamentTree(ornaments, starLit, Modifier.size(width = 72.dp, height = 84.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.advent_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF4F1E8),
                )
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC9D6CC))
            }
            Surface(
                onClick = onOpen,
                shape = CircleShape,
                color = if (ready) ChristmasGold else Color.White.copy(alpha = 0.12f),
                contentColor = if (ready) Color(0xFF2B2100) else Color(0xFFF4F1E8),
                modifier = Modifier.height(36.dp),
            ) {
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.advent_card_open), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
