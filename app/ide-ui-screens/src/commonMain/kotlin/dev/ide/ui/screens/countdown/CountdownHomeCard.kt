package dev.ide.ui.screens.countdown

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
import dev.ide.ui.components.AuroraTeal
import dev.ide.ui.components.WinterNavy
import dev.ide.ui.components.WinterNavyTop
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.countdown_card_done
import dev.ide.ui.generated.resources.countdown_card_open
import dev.ide.ui.generated.resources.countdown_card_ready
import dev.ide.ui.generated.resources.countdown_card_title
import dev.ide.ui.generated.resources.countdown_card_tomorrow
import dev.ide.ui.generated.resources.countdown_card_waiting
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.holiday.SkyStar
import org.jetbrains.compose.resources.stringResource

/** What the Home card says about the countdown, worked out from the ledger. */
sealed interface CountdownCardStatus {
    /** Before December 1. */
    data object Waiting : CountdownCardStatus
    /** The earliest day whose date has come and that is still closed. */
    data class Ready(val door: Int) : CountdownCardStatus
    /** Every day so far is open; [door] opens tomorrow. */
    data class Tomorrow(val door: Int) : CountdownCardStatus
    /** All 31 are open. */
    data object Done : CountdownCardStatus

    companion object {
        fun of(ledger: HolidayLedger): CountdownCardStatus {
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
 * The countdown's way in from Home: the sky as it stands, and whether a day is waiting. The whole card opens
 * the countdown; a ready day lights its button, the only nudge it gives.
 */
@Composable
fun CountdownHomeCard(
    status: CountdownCardStatus,
    stars: List<SkyStar>,
    auroraOut: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = status is CountdownCardStatus.Ready
    val line = when (status) {
        CountdownCardStatus.Waiting -> stringResource(Res.string.countdown_card_waiting)
        is CountdownCardStatus.Ready -> stringResource(Res.string.countdown_card_ready, status.door)
        is CountdownCardStatus.Tomorrow -> stringResource(Res.string.countdown_card_tomorrow, status.door)
        CountdownCardStatus.Done -> stringResource(Res.string.countdown_card_done)
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(WinterNavyTop, WinterNavy)))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NightSky(stars, auroraOut, Modifier.size(width = 96.dp, height = 72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.countdown_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF2F4FA),
                )
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC3CADF))
            }
            Surface(
                onClick = onOpen,
                shape = CircleShape,
                color = if (ready) AuroraTeal else Color.White.copy(alpha = 0.12f),
                contentColor = if (ready) Color(0xFF00382F) else Color(0xFFF2F4FA),
                modifier = Modifier.height(36.dp),
            ) {
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.countdown_card_open), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
