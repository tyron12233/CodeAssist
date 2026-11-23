package dev.ide.ui.screens.countdown

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.AuroraTeal
import dev.ide.ui.components.AuroraViolet
import dev.ide.ui.components.CodeSample
import dev.ide.ui.components.StarGold
import dev.ide.ui.components.WinterBackdrop
import dev.ide.ui.components.WinterLightColors
import dev.ide.ui.components.WinterNavy
import dev.ide.ui.components.WinterNavyTop
import dev.ide.ui.components.drawAurora
import dev.ide.ui.components.drawFairyLight
import dev.ide.ui.components.drawFirework
import dev.ide.ui.components.drawSkyStar
import dev.ide.ui.components.drawSnowflake
import dev.ide.ui.components.drawSparkle
import dev.ide.ui.components.drawStar
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.countdown_challenge_label
import dev.ide.ui.generated.resources.countdown_challenge_not_ready
import dev.ide.ui.generated.resources.countdown_challenge_note
import dev.ide.ui.generated.resources.countdown_close
import dev.ide.ui.generated.resources.countdown_day_locked
import dev.ide.ui.generated.resources.countdown_days_left
import dev.ide.ui.generated.resources.countdown_open_lesson
import dev.ide.ui.generated.resources.countdown_open_store
import dev.ide.ui.generated.resources.countdown_opens_in_days
import dev.ide.ui.generated.resources.countdown_scheme_applied
import dev.ide.ui.generated.resources.countdown_scheme_unlocked
import dev.ide.ui.generated.resources.countdown_sky_title
import dev.ide.ui.generated.resources.countdown_solve
import dev.ide.ui.generated.resources.countdown_star_earned
import dev.ide.ui.generated.resources.countdown_subtitle
import dev.ide.ui.generated.resources.countdown_title
import dev.ide.ui.generated.resources.countdown_try_scheme
import dev.ide.ui.holiday.DoorState
import dev.ide.ui.holiday.HolidayActivity
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.holiday.SkyStar
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.screens.ColorSchemePreview
import dev.ide.ui.screens.ColorSchemeSamples
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import dev.ide.ui.theme.colors.ColorSchemeStore
import dev.ide.ui.theme.colors.ResolvedColorScheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The New Year countdown: the night sky the season's stars appear in, and the 31 days of December. A day
 * whose date has come glows; opening it adds a star and shows what was behind it in a sheet: a challenge every
 * day through the 30th, and on many days a little extra (a tip, a keepsake to try on, the lesson, the Store).
 * New Year's Eve brings the aurora out over the sky.
 *
 * All state is the [ledger]'s and the color [schemes]'; this screen re-reads both after anything it changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownScreen(
    backend: IdeBackend,
    ledger: HolidayLedger,
    schemes: ColorSchemeStore,
    onBack: () -> Unit,
    onSolve: (date: String) -> Unit,
    onOpenLesson: (trackId: String) -> Unit,
    onOpenStore: () -> Unit,
    onSchemeApplied: () -> Unit,
    modifier: Modifier = Modifier,
    /** The day to show open on arrival (a snapshot's hook); null for none. */
    initialDay: Int? = null,
) {
    var version by remember { mutableIntStateOf(0) }
    val year = remember { ledger.seasonYear() }
    // Re-read on every change; both are a handful of preference reads.
    val opened = remember(version) { ledger.opened(year) }
    val stars = remember(version) { ledger.stars(year) }
    val unlockedThrough = remember(version) { ledger.unlockedThrough() }
    var shown by remember { mutableStateOf(initialDay) }
    var earned by remember { mutableStateOf(false) }

    fun openDay(day: Int) {
        val before = ledger.stars(year).size
        if (ledger.state(day) == DoorState.Ready) {
            ledger.open(day)
            CountdownDays.of(day).bonus?.takeIf { it.kind == DayKind.Keepsake }?.payload?.let(schemes::unlockKeepsake)
        }
        earned = ledger.stars(year).size > before
        shown = day
        version++
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WinterBackdrop()
        Column(Modifier.fillMaxSize()) {
            CountdownTopBar(onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SkyCard(stars, auroraOut = HolidayLedger.DOORS in opened, daysLeft = remember(version) { ledger.daysToNewYear() }, modifier = Modifier.widthIn(max = 560.dp))
                Text(
                    stringResource(Res.string.countdown_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
                val waiting = ledger.daysUntilFirstDoor()
                if (waiting > 0) {
                    Text(
                        pluralStringResource(Res.plurals.countdown_opens_in_days, waiting, waiting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DayGrid(
                    opened = opened,
                    unlockedThrough = unlockedThrough,
                    onDay = ::openDay,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
        }
    }

    shown?.let { day ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { shown = null; earned = false }, sheetState = sheet) {
            DaySheet(
                backend = backend,
                entry = CountdownDays.of(day),
                state = ledger.state(day),
                year = year,
                earned = earned,
                schemes = schemes,
                onSolve = { onSolve(it); shown = null },
                onOpenLesson = { onOpenLesson(it); shown = null },
                onOpenStore = { onOpenStore(); shown = null },
                onSchemeApplied = onSchemeApplied,
                onClose = { shown = null; earned = false },
            )
        }
    }
}

@Composable
private fun CountdownTopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(CaSymbols.arrowBack, contentDescription = stringResource(Res.string.back), size = 22.dp, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            stringResource(Res.string.countdown_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// The sky
// ---------------------------------------------------------------------------

@Composable
private fun SkyCard(stars: List<SkyStar>, auroraOut: Boolean, daysLeft: Int?, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = WinterNavy, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.countdown_sky_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = SkyText)
            if (daysLeft != null) {
                Text(
                    pluralStringResource(Res.plurals.countdown_days_left, daysLeft, daysLeft),
                    style = MaterialTheme.typography.labelLarge,
                    color = StarGold,
                )
            }
            NightSky(stars, auroraOut, Modifier.fillMaxWidth().height(230.dp).padding(horizontal = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                for (activity in HolidayActivity.entries) {
                    val count = stars.count { it.activity == activity }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(14.dp)) { drawSkyStar(Offset(size.width / 2f, size.height / 2f), size.width * 0.5f, starColor(activity)) }
                        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = SkyBody)
                    }
                }
            }
        }
    }
}

/**
 * The night sky with the season's [stars] in it, in the order they were earned. Stars belong to fixed
 * constellations spread across the sky, and a line joins two stars of the same constellation as soon as both
 * are lit, so the shapes draw themselves as the month goes on. A star never moves once it appears; the newest
 * one swells into place. With [auroraOut] (New Year's Eve opened), the northern lights hang over it all.
 */
@Composable
fun NightSky(stars: List<SkyStar>, auroraOut: Boolean, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(stars.size) {
        pop.snapTo(0f)
        pop.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    val background = remember { faintStars(Random(2027)) }
    Canvas(modifier.clip(RoundedCornerShape(20.dp))) {
        drawRect(Brush.verticalGradient(listOf(WinterNavyTop, WinterNavy)))
        if (auroraOut) drawAurora(top = 0f, height = size.height * 0.6f, phase = 0.9f, strength = 1f)
        // The far stars, always there, so an empty sky is still a sky.
        for ((x, y, r) in background) drawCircle(Color.White.copy(alpha = 0.18f), r * 1.dp.toPx(), Offset(x * size.width, y * size.height))
        val slots = constellationSlots()
        val lit = stars.size.coerceAtMost(slots.size)
        val unit = size.minDimension
        // Lines first, under the stars.
        for (i in 1 until lit) {
            val a = slots[i - 1]
            val b = slots[i]
            if (a.group == b.group) {
                drawLine(SkyLine, a.at(size), b.at(size), strokeWidth = 1.dp.toPx())
            }
        }
        for (i in 0 until lit) {
            val grow = if (i == lit - 1) pop.value else 1f
            drawSkyStar(slots[i].at(size), unit * 0.035f * grow, starColor(stars[i].activity))
        }
    }
}

/** One place a star can appear: [x], [y] as fractions of the sky, in constellation [group]. */
private class Slot(val x: Float, val y: Float, val group: Int) {
    fun at(size: androidx.compose.ui.geometry.Size) = Offset(x * size.width, y * size.height)
}

/**
 * The sky's [HolidayLedger.SKY_CAPACITY] places, in lighting order: ten constellations of six stars, each a
 * short random walk inside its own patch of a 5 x 2 grid, visited in an order that alternates across the sky so
 * the early stars spread out. Seeded, so the sky is the same every time it is drawn.
 */
private fun constellationSlots(): List<Slot> = SLOTS

private val SLOTS: List<Slot> = run {
    val r = Random(31)
    val patches = listOf(0, 7, 3, 9, 5, 2, 8, 1, 6, 4)
    val out = ArrayList<Slot>(HolidayLedger.SKY_CAPACITY)
    for ((group, patch) in patches.withIndex()) {
        val col = patch % 5
        val row = patch / 5
        val left = 0.04f + col * 0.19f
        val top = 0.1f + row * 0.44f
        var x = left + 0.04f + r.nextFloat() * 0.1f
        var y = top + 0.06f + r.nextFloat() * 0.25f
        var heading = r.nextFloat() * 2f * PI.toFloat()
        repeat(HolidayLedger.SKY_CAPACITY / 10) {
            out += Slot(x, y, group)
            heading += (r.nextFloat() - 0.5f) * 1.6f
            x = (x + cos(heading) * 0.05f).coerceIn(left + 0.01f, left + 0.17f)
            y = (y + sin(heading) * 0.09f).coerceIn(top + 0.02f, top + 0.38f)
        }
    }
    out
}

private fun faintStars(r: Random): List<Triple<Float, Float, Float>> = List(40) { Triple(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.8f) }

/** The color a star takes for what earned it. */
fun starColor(activity: HolidayActivity): Color = when (activity) {
    HolidayActivity.Door -> Color(0xFFFFF1C9)
    HolidayActivity.Challenge -> StarGold
    HolidayActivity.Lesson -> Color(0xFF8FC8FF)
    HolidayActivity.Build -> AuroraTeal
    HolidayActivity.Publish -> AuroraViolet
}

// ---------------------------------------------------------------------------
// The days
// ---------------------------------------------------------------------------

@Composable
private fun DayGrid(opened: Set<Int>, unlockedThrough: Int, onDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 5) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until COLUMNS) {
                    val day = row * COLUMNS + col + 1
                    if (day > HolidayLedger.DOORS) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val state = when {
                        day in opened -> DoorState.Opened
                        day <= unlockedThrough -> DoorState.Ready
                        else -> DoorState.Locked
                    }
                    DayTile(day, state, Modifier.weight(1f)) { onDay(day) }
                }
            }
        }
    }
}

/**
 * One day. A locked day is a quiet tile with its number; a day whose date has come wears its own night color
 * with a gold rim; an opened one shows what was behind it. New Year's Eve is a little brighter than the rest.
 */
@Composable
private fun DayTile(day: Int, state: DoorState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tone = if (day == HolidayLedger.DOORS) Color(0xFF6A4FB8) else DayTones[(day * 3) % DayTones.size]
    val dark = c.background.luminance() < 0.5f
    val shape = RoundedCornerShape(14.dp)
    val fill = when (state) {
        DoorState.Locked -> c.surfaceContainerHigh
        DoorState.Ready -> tone
        DoorState.Opened -> lerp(tone, c.surfaceContainer, if (dark) 0.7f else 0.8f)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(fill)
            .then(if (state == DoorState.Ready) Modifier.border(2.dp, StarGold, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DoorState.Opened -> Canvas(Modifier.fillMaxSize().padding(9.dp)) { drawDayKind(CountdownDays.of(day).iconKind) }
            else -> Text(
                day.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (state == DoorState.Ready) FontWeight.Bold else FontWeight.Medium,
                color = if (state == DoorState.Ready) Color(0xFFF7F8FF) else c.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (state == DoorState.Opened) {
            Text(
                day.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp),
            )
        }
    }
}

/** The small picture of what a day held, filling this canvas. */
private fun DrawScope.drawDayKind(kind: DayKind) {
    val s = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)
    when (kind) {
        DayKind.Tip -> drawSnowflake(c, s * 0.42f, Color(0xFFBFD8FF), 15f)
        DayKind.Challenge -> drawStar(c, s * 0.44f, StarGold)
        DayKind.Keepsake -> {
            val r = s * 0.36f
            drawCircle(Brush.sweepGradient(listOf(AuroraTeal, AuroraViolet, StarGold, AuroraTeal), c), r, c)
            drawCircle(Color.White.copy(alpha = 0.35f), r * 0.25f, c - Offset(r * 0.35f, r * 0.35f))
        }
        DayKind.Lesson -> {
            drawSparkle(c, s * 0.42f, Color(0xFF8FC8FF))
            drawCircle(Color.White, s * 0.06f, c)
        }
        DayKind.Store -> for (i in 0 until 3) {
            drawFairyLight(Offset(c.x + (i - 1) * s * 0.3f, c.y + (if (i == 1) s * 0.08f else -s * 0.04f)), s * 0.09f, WinterLightColors[i])
        }
        DayKind.Finale -> {
            drawFirework(c, s * 0.48f, 0.55f, StarGold, seed = 1, sparks = 12)
            drawFirework(c, s * 0.3f, 0.5f, AuroraTeal, seed = 4, sparks = 9)
        }
    }
}

// ---------------------------------------------------------------------------
// The sheet behind a day
// ---------------------------------------------------------------------------

@Composable
private fun DaySheet(
    backend: IdeBackend,
    entry: CountdownDay,
    state: DoorState,
    year: Int,
    earned: Boolean,
    schemes: ColorSchemeStore,
    onSolve: (String) -> Unit,
    onOpenLesson: (String) -> Unit,
    onOpenStore: () -> Unit,
    onSchemeApplied: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == DoorState.Locked) {
            Text(
                stringResource(Res.string.countdown_day_locked, entry.day),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SheetButton(stringResource(Res.string.countdown_close), primary = false, onClick = onClose)
            return@Column
        }
        val bonus = entry.bonus
        if (bonus?.kind == DayKind.Finale) Fireworks(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)))
        // The header names the day's main event: the bonus when there is one (a keepsake, the lesson, a tip),
        // otherwise the challenge.
        val headTitle = bonus?.title ?: entry.challenge?.title
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(WinterNavy), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(40.dp)) { drawDayKind(entry.iconKind) }
            }
            Column(Modifier.weight(1f)) {
                Text(entry.day.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (headTitle != null) Text(stringResource(headTitle), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        if (earned) EarnedChip()
        if (bonus != null) {
            Text(stringResource(bonus.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bonus.code?.let { CodeSample(it, bonus.codeLanguage) }
            when (bonus.kind) {
                DayKind.Keepsake -> KeepsakeAction(bonus, schemes, onSchemeApplied)
                DayKind.Lesson -> SheetButton(stringResource(Res.string.countdown_open_lesson)) { bonus.payload?.let(onOpenLesson) }
                DayKind.Store -> SheetButton(stringResource(Res.string.countdown_open_store), onClick = onOpenStore)
                // The finale's own words already say the aurora is out; the fireworks above are its action.
                DayKind.Finale, DayKind.Tip, DayKind.Challenge -> {}
            }
        }
        entry.challenge?.let { challenge ->
            ChallengeCard(
                backend = backend,
                challenge = challenge,
                date = entry.challengeDate(year),
                // On a challenge-only day the header already carries its title.
                showTitle = bonus != null,
                onSolve = onSolve,
            )
        }
    }
}

/**
 * The day's challenge: its name and a line of story, and the way in. Solve is only offered once the Daily
 * Challenge actually has a problem on that date, so a day can never send someone to an empty challenge.
 */
@Composable
private fun ChallengeCard(backend: IdeBackend, challenge: DayChallenge, date: String, showTitle: Boolean, onSolve: (String) -> Unit) {
    val ready by produceState<Boolean?>(null, date) {
        value = backend.challenges.challengesAvailable() &&
            runCatching { backend.challenges.day(date).scheduled }.getOrDefault(false)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(18.dp)) { drawStar(Offset(size.width / 2f, size.height / 2f), size.width * 0.45f, StarGold) }
                Text(stringResource(Res.string.countdown_challenge_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (showTitle) Text(stringResource(challenge.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(challenge.teaser), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.countdown_challenge_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            when (ready) {
                true -> SheetButton(stringResource(Res.string.countdown_solve)) { onSolve(date) }
                false -> Text(stringResource(Res.string.countdown_challenge_not_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                null -> Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** New Year's Eve: bursts going off one after another over a night sky, for as long as the sheet is open. */
@Composable
private fun Fireworks(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "fireworks")
    val clock = t.animateFloat(0f, 1f, infiniteRepeatable(tween(FIREWORKS_MS, easing = LinearEasing), RepeatMode.Restart), label = "fireworksClock")
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(WinterNavy, WinterNavyTop)))
        drawAurora(top = 0f, height = size.height * 0.7f, phase = clock.value * 6.28f, strength = 0.7f)
        for ((i, b) in Bursts.withIndex()) {
            val p = ((clock.value - b.start + 1f) % 1f) / 0.55f
            drawFirework(Offset(b.x * size.width, b.y * size.height), size.height * b.size, p, WinterLightColors[i % WinterLightColors.size], seed = i)
        }
    }
}

private class Burst(val x: Float, val y: Float, val size: Float, val start: Float)

private val Bursts = listOf(
    Burst(0.22f, 0.42f, 0.38f, 0f),
    Burst(0.7f, 0.35f, 0.45f, 0.22f),
    Burst(0.48f, 0.55f, 0.32f, 0.45f),
    Burst(0.85f, 0.6f, 0.3f, 0.65f),
    Burst(0.12f, 0.7f, 0.28f, 0.8f),
)

@Composable
private fun EarnedChip() {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(16.dp)) { drawSkyStar(Offset(size.width / 2f, size.height / 2f), size.width * 0.5f, StarGold) }
        Text(stringResource(Res.string.countdown_star_earned), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun KeepsakeAction(bonus: DayBonus, schemes: ColorSchemeStore, onSchemeApplied: () -> Unit) {
    val scheme = bonus.payload?.let(BuiltInColorSchemes::keepsake) ?: return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val resolved = remember(scheme, dark) { ResolvedColorScheme(scheme, dark) }
    var applied by remember { mutableStateOf(schemes.activeId() == scheme.id) }
    // The first lines of the sample are enough to see a palette; the whole of it would fill the sheet.
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))) {
        ColorSchemePreview(resolved, ColorSchemeSamples.KOTLIN, onPickAttribute = {})
    }
    Text(
        stringResource(if (applied) Res.string.countdown_scheme_applied else Res.string.countdown_scheme_unlocked, scheme.name),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
    )
    if (!applied) {
        SheetButton(stringResource(Res.string.countdown_try_scheme)) {
            schemes.setActiveId(scheme.id)
            applied = true
            onSchemeApplied()
        }
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (primary) c.primary else c.surfaceContainerHigh,
        contentColor = if (primary) c.onPrimary else c.onSurface,
        modifier = Modifier.height(44.dp),
    ) {
        Box(Modifier.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val COLUMNS = 7
private const val FIREWORKS_MS = 4200

private val SkyText = Color(0xFFF2F4FA)
private val SkyBody = Color(0xFFC3CADF)
private val SkyLine = Color.White.copy(alpha = 0.22f)

/** The night colors a ready day wears, by date: close enough to sit together in a grid. */
private val DayTones = listOf(
    Color(0xFF2E5E8A), // winter blue
    Color(0xFF1F7A6C), // aurora teal
    Color(0xFF4E4A9E), // twilight violet
    Color(0xFF8A6A2E), // old gold
    Color(0xFF3B4F7A), // dusk
)
