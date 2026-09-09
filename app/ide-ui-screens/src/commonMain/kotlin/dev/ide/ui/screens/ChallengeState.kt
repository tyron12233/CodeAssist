package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeBoard
import dev.ide.ui.backend.UiChallengeDay
import dev.ide.ui.backend.UiChallengeProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * What the Challenges tab has loaded.
 *
 * The three reads are independent and are issued together: the problem, the board and the viewer's own
 * history come from different functions and none of them needs to wait for another.
 */
class ChallengeTabState(
    val day: UiChallengeDay = UiChallengeDay(),
    val board: UiChallengeBoard = UiChallengeBoard(),
    val profile: UiChallengeProfile = UiChallengeProfile(),
    val loading: Boolean = true,
    val offline: Boolean = false,
)

/** Loads the tab's three payloads, re-running whenever [epoch] changes (a solve, a sign-in, a refresh). */
@Composable
fun rememberChallengeTab(backend: IdeBackend, epoch: Int): ChallengeTabState {
    var state by remember(backend) { mutableStateOf(ChallengeTabState()) }
    LaunchedEffect(backend, epoch) {
        state = ChallengeTabState(day = state.day, board = state.board, profile = state.profile, loading = true)
        val loaded = coroutineScope {
            val day = async { runCatching { backend.challenges.day() }.getOrDefault(UiChallengeDay()) }
            val board = async { runCatching { backend.challenges.board(limit = 5) }.getOrDefault(UiChallengeBoard()) }
            val profile = async { runCatching { backend.challenges.profile() }.getOrDefault(UiChallengeProfile()) }
            Triple(day.await(), board.await(), profile.await())
        }
        state = ChallengeTabState(
            day = loaded.first,
            board = loaded.second,
            profile = loaded.third,
            loading = false,
            offline = loaded.first.reason == "offline",
        )
    }
    return state
}

/**
 * A clock that ticks once a second while it is on screen.
 *
 * Frame-driven rather than a timer, so it stops the moment the composable leaves and never keeps a
 * coroutine alive behind a screen nobody is looking at.
 */
@Composable
fun rememberSecondsTicker(): Long {
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { frame ->
                if (frame - last >= 1000L) {
                    last = frame
                    now = frame
                }
            }
        }
    }
    return now
}

/** Formats a duration for the countdown to the next drop: `06:12:44`. */
fun formatCountdown(millis: Long): String {
    if (millis <= 0) return "00:00:00"
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return pad(h) + ":" + pad(m) + ":" + pad(s)
}

/** Formats how long a solve took, in the largest unit that stays readable. */
fun formatSolveTime(millis: Long): String {
    if (millis <= 0) return "-"
    val total = millis / 1000
    return when {
        total < 60 -> total.toString() + "s"
        total < 3600 -> (total / 60).toString() + "m " + pad(total % 60) + "s"
        else -> (total / 3600).toString() + "h " + pad((total % 3600) / 60) + "m"
    }
}

/**
 * Formats a measured runtime.
 *
 * Nanoseconds are never shown: they are precise well past the point of meaning anything, and a row of
 * nine digits is harder to compare at a glance than three.
 */
fun formatRuntime(ns: Long): String = when {
    ns <= 0 -> "-"
    ns < 1_000 -> ns.toString() + " ns"
    ns < 1_000_000 -> oneDecimal(ns / 1_000.0) + " us"
    ns < 1_000_000_000 -> oneDecimal(ns / 1_000_000.0) + " ms"
    else -> oneDecimal(ns / 1_000_000_000.0) + " s"
}

/** Formats measured allocation. Named apart from the other size formatters in this package, which are
 *  private to their screens and use different rounding. */
fun formatAllocated(bytes: Long): String = when {
    bytes <= 0 -> "-"
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> oneDecimal(bytes / 1024.0) + " KB"
    else -> oneDecimal(bytes / (1024.0 * 1024.0)) + " MB"
}

private fun pad(v: Long): String = if (v < 10) "0" + v else v.toString()

private fun oneDecimal(v: Double): String {
    val scaled = kotlin.math.round(v * 10).toLong()
    return (scaled / 10).toString() + "." + (scaled % 10).toString()
}
