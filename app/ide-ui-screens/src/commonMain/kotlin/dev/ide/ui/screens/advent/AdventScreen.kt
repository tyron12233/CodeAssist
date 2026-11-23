package dev.ide.ui.screens.advent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.ChristmasBackdrop
import dev.ide.ui.components.ChristmasCranberry
import dev.ide.ui.components.ChristmasGold
import dev.ide.ui.components.ChristmasLightColors
import dev.ide.ui.components.CodeSample
import dev.ide.ui.components.drawFirTree
import dev.ide.ui.components.drawGift
import dev.ide.ui.components.drawOrnament
import dev.ide.ui.components.drawSnowflake
import dev.ide.ui.components.drawStar
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.advent_challenge_not_ready
import dev.ide.ui.generated.resources.advent_challenge_note
import dev.ide.ui.generated.resources.advent_close
import dev.ide.ui.generated.resources.advent_door_locked
import dev.ide.ui.generated.resources.advent_open_lesson
import dev.ide.ui.generated.resources.advent_open_store
import dev.ide.ui.generated.resources.advent_opens_in_days
import dev.ide.ui.generated.resources.advent_ornament_earned
import dev.ide.ui.generated.resources.advent_scheme_applied
import dev.ide.ui.generated.resources.advent_scheme_unlocked
import dev.ide.ui.generated.resources.advent_solve
import dev.ide.ui.generated.resources.advent_star_lit
import dev.ide.ui.generated.resources.advent_subtitle
import dev.ide.ui.generated.resources.advent_title
import dev.ide.ui.generated.resources.advent_tree_title
import dev.ide.ui.generated.resources.advent_try_scheme
import dev.ide.ui.generated.resources.back
import dev.ide.ui.holiday.DoorState
import dev.ide.ui.holiday.HolidayActivity
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.holiday.Ornament
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
import kotlin.random.Random

/**
 * The Advent calendar: the tree the season's ornaments hang on, and the 25 doors. A door whose day has come
 * glows; opening it hangs an ornament and shows what was behind it in a sheet, with whatever it leads to (a
 * challenge to solve, a keepsake to try on, the lesson, the Store).
 *
 * All state is the [ledger]'s and the color [schemes]'; this screen re-reads both after anything it changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventScreen(
    backend: IdeBackend,
    ledger: HolidayLedger,
    schemes: ColorSchemeStore,
    onBack: () -> Unit,
    onSolve: (date: String) -> Unit,
    onOpenLesson: (trackId: String) -> Unit,
    onOpenStore: () -> Unit,
    onSchemeApplied: () -> Unit,
    modifier: Modifier = Modifier,
    /** The door to show open on arrival (a snapshot's hook); null for none. */
    initialDoor: Int? = null,
) {
    var version by remember { mutableIntStateOf(0) }
    val year = remember { ledger.seasonYear() }
    // Re-read on every change; both are a handful of preference reads.
    val opened = remember(version) { ledger.opened(year) }
    val ornaments = remember(version) { ledger.ornaments(year) }
    val unlockedThrough = remember(version) { ledger.unlockedThrough() }
    var shown by remember { mutableStateOf(initialDoor) }
    var earned by remember { mutableStateOf(false) }

    fun openDoor(day: Int) {
        val before = ledger.ornaments(year).size
        if (ledger.state(day) == DoorState.Ready) {
            ledger.open(day)
            AdventDoors.of(day).takeIf { it.kind == DoorKind.Keepsake }?.payload?.let(schemes::unlockKeepsake)
        }
        earned = ledger.ornaments(year).size > before
        shown = day
        version++
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChristmasBackdrop()
        Column(Modifier.fillMaxSize()) {
            AdventTopBar(onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TreeCard(ornaments, starLit = 25 in opened, modifier = Modifier.widthIn(max = 560.dp))
                Text(
                    stringResource(Res.string.advent_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
                val waiting = ledger.daysUntilFirstDoor()
                if (waiting > 0) {
                    Text(
                        pluralStringResource(Res.plurals.advent_opens_in_days, waiting, waiting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DoorGrid(
                    opened = opened,
                    unlockedThrough = unlockedThrough,
                    onDoor = ::openDoor,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
        }
    }

    shown?.let { day ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { shown = null; earned = false }, sheetState = sheet) {
            DoorSheet(
                backend = backend,
                door = AdventDoors.of(day),
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
private fun AdventTopBar(onBack: () -> Unit) {
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
            stringResource(Res.string.advent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// The tree
// ---------------------------------------------------------------------------

@Composable
private fun TreeCard(ornaments: List<Ornament>, starLit: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = NightTop,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(Res.string.advent_tree_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = NightText,
            )
            OrnamentTree(ornaments, starLit, Modifier.fillMaxWidth().height(260.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                for (activity in HolidayActivity.entries) {
                    val count = ornaments.count { it.activity == activity }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Canvas(Modifier.size(14.dp)) { drawOrnament(Offset(size.width / 2f, size.height * 0.58f), size.width * 0.4f, ornamentColor(activity)) }
                        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = NightBody)
                    }
                }
            }
        }
    }
}

/**
 * The fir with the season's [ornaments] hung on it in the order they were earned. Slots are fixed and spread
 * by a seeded shuffle, so the first few ornaments land all over the tree rather than in one corner, and an
 * ornament never moves once hung. The star is lit when Christmas Day's door has been opened.
 */
@Composable
fun OrnamentTree(ornaments: List<Ornament>, starLit: Boolean, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(ornaments.size) {
        pop.snapTo(0f)
        pop.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val h = size.height * 0.94f
        val base = Offset(size.width / 2f, size.height * 0.97f)
        drawFirTree(base, h, lights = if (starLit) 1f else 0.55f, lightPhase = 0.6f)
        if (starLit) {
            drawStar(Offset(base.x, base.y - h * 0.88f), h * 0.1f, ChristmasGold, glow = 1f)
        }
        val slots = ornamentSlots(base, h)
        for ((i, o) in ornaments.withIndex()) {
            if (i >= slots.size) break
            // The newest ornament swells into place.
            val grow = if (i == ornaments.lastIndex) pop.value else 1f
            drawOrnament(slots[i], h * 0.028f * grow, ornamentColor(o.activity))
        }
    }
}

/** Where the ornaments hang, in hanging order. Rows narrow toward the top like the tree does. */
private fun DrawScope.ornamentSlots(base: Offset, height: Float): List<Offset> {
    val perRow = intArrayOf(7, 7, 6, 6, 5, 4, 3, 2)
    val rise = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.69f, 0.77f)
    val out = ArrayList<Offset>(HolidayLedger.TREE_CAPACITY)
    for (r in perRow.indices) {
        val f = rise[r]
        val y = base.y - height * f
        // Half the tree's width at this height, kept inside the branches.
        val half = height * 0.36f * (1f - (f - 0.06f) / 0.86f) * 0.8f
        val n = perRow[r]
        for (k in 0 until n) {
            val t = if (n == 1) 0.5f else k / (n - 1f)
            val x = base.x - half + 2f * half * t
            // A little stagger so the rows don't read as a grid.
            out += Offset(x + (if (r % 2 == 0) 0.3f else -0.3f) * half / n, y + (if (k % 2 == 0) 0f else height * 0.012f))
        }
    }
    val order = out.indices.shuffled(Random(25))
    return order.map { out[it] }
}

/** The color an ornament takes for what earned it. */
fun ornamentColor(activity: HolidayActivity): Color = when (activity) {
    HolidayActivity.Door -> ChristmasCranberry
    HolidayActivity.Challenge -> ChristmasGold
    HolidayActivity.Lesson -> Color(0xFF8DB8E8)
    HolidayActivity.Build -> Color(0xFF9FD4B0)
    HolidayActivity.Publish -> Color(0xFFF2B880)
}

// ---------------------------------------------------------------------------
// The doors
// ---------------------------------------------------------------------------

@Composable
private fun DoorGrid(opened: Set<Int>, unlockedThrough: Int, onDoor: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0 until 5) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in 0 until 5) {
                    val day = row * 5 + col + 1
                    val state = when {
                        day in opened -> DoorState.Opened
                        day <= unlockedThrough -> DoorState.Ready
                        else -> DoorState.Locked
                    }
                    AdventDoorTile(day, state, Modifier.weight(1f)) { onDoor(day) }
                }
            }
        }
    }
}

/**
 * One door. A locked door is a quiet tile with its number; a door whose day has come wears its own wrapping
 * color with a gold rim; an opened one shows what was behind it.
 */
@Composable
private fun AdventDoorTile(day: Int, state: DoorState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val wrap = DoorWraps[(day * 7) % DoorWraps.size]
    val dark = c.background.luminance() < 0.5f
    val shape = RoundedCornerShape(16.dp)
    val fill = when (state) {
        DoorState.Locked -> c.surfaceContainerHigh
        DoorState.Ready -> wrap
        DoorState.Opened -> lerp(wrap, c.surfaceContainer, if (dark) 0.72f else 0.8f)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(fill)
            .then(if (state == DoorState.Ready) Modifier.border(2.dp, ChristmasGold, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DoorState.Opened -> Canvas(Modifier.fillMaxSize().padding(12.dp)) { drawDoorKind(AdventDoors.of(day).kind) }
            else -> Text(
                day.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (state == DoorState.Ready) FontWeight.Bold else FontWeight.Medium,
                color = if (state == DoorState.Ready) Color(0xFFFFF8EC) else c.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (state == DoorState.Opened) {
            Text(
                day.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 4.dp),
            )
        }
    }
}

/** The small picture of what a door held, filling this canvas. */
private fun DrawScope.drawDoorKind(kind: DoorKind) {
    val s = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)
    when (kind) {
        DoorKind.Tip -> drawSnowflake(c, s * 0.42f, ChristmasLightColors[4], 15f)
        DoorKind.Challenge -> drawStar(c, s * 0.44f, ChristmasGold)
        DoorKind.Keepsake -> drawOrnament(Offset(c.x, c.y + s * 0.06f), s * 0.34f, ChristmasLightColors[2])
        DoorKind.Lesson -> drawGift(c, s * 0.78f, s * 0.72f, wrap = Color(0xFF5B8FB9))
        DoorKind.Store -> drawGift(c, s * 0.78f, s * 0.72f)
        DoorKind.Finale -> drawFirTree(Offset(c.x, c.y + s * 0.48f), s * 0.98f, lights = 1f)
    }
}

// ---------------------------------------------------------------------------
// The sheet behind a door
// ---------------------------------------------------------------------------

@Composable
private fun DoorSheet(
    backend: IdeBackend,
    door: AdventDoor,
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
                stringResource(Res.string.advent_door_locked, door.day),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SheetButton(stringResource(Res.string.advent_close), primary = false, onClick = onClose)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(NightTop),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(40.dp)) { drawDoorKind(door.kind) }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    door.day.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(door.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        if (earned) EarnedChip()
        Text(stringResource(door.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        door.code?.let { CodeSample(it, door.codeLanguage) }
        when (door.kind) {
            DoorKind.Challenge -> ChallengeAction(backend, door.challengeDate(year), onSolve)
            DoorKind.Keepsake -> KeepsakeAction(door, schemes, onSchemeApplied)
            DoorKind.Lesson -> SheetButton(stringResource(Res.string.advent_open_lesson)) { door.payload?.let(onOpenLesson) }
            DoorKind.Store -> SheetButton(stringResource(Res.string.advent_open_store), onClick = onOpenStore)
            DoorKind.Finale -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(22.dp)) { drawStar(Offset(size.width / 2f, size.height / 2f), size.width * 0.42f, ChristmasGold, glow = 1f) }
                Text(stringResource(Res.string.advent_star_lit), style = MaterialTheme.typography.titleSmall)
            }
            DoorKind.Tip -> {}
        }
    }
}

@Composable
private fun EarnedChip() {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(14.dp)) { drawOrnament(Offset(size.width / 2f, size.height * 0.58f), size.width * 0.4f, ornamentColor(HolidayActivity.Door)) }
        Text(stringResource(Res.string.advent_ornament_earned), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * The challenge door's action. Only offered once the Daily Challenge actually has a problem on that date, so a
 * door can never send someone to an empty day.
 */
@Composable
private fun ChallengeAction(backend: IdeBackend, date: String, onSolve: (String) -> Unit) {
    val ready by produceState<Boolean?>(null, date) {
        value = backend.challenges.challengesAvailable() &&
            runCatching { backend.challenges.day(date).scheduled }.getOrDefault(false)
    }
    Text(stringResource(Res.string.advent_challenge_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    when (ready) {
        true -> SheetButton(stringResource(Res.string.advent_solve)) { onSolve(date) }
        false -> Text(stringResource(Res.string.advent_challenge_not_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        null -> Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun KeepsakeAction(door: AdventDoor, schemes: ColorSchemeStore, onSchemeApplied: () -> Unit) {
    val scheme = door.payload?.let(BuiltInColorSchemes::keepsake) ?: return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val resolved = remember(scheme, dark) { ResolvedColorScheme(scheme, dark) }
    var applied by remember { mutableStateOf(schemes.activeId() == scheme.id) }
    // The first lines of the sample are enough to see a palette; the whole of it would fill the sheet.
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))) {
        ColorSchemePreview(resolved, ColorSchemeSamples.KOTLIN, onPickAttribute = {})
    }
    Text(
        stringResource(if (applied) Res.string.advent_scheme_applied else Res.string.advent_scheme_unlocked, scheme.name),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
    )
    if (!applied) {
        SheetButton(stringResource(Res.string.advent_try_scheme)) {
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

private val NightTop = Color(0xFF173528)
private val NightText = Color(0xFFF4F1E8)
private val NightBody = Color(0xFFC9D6CC)

/** The wrapping a ready door wears, by day: muted enough to sit together in a grid. */
private val DoorWraps = listOf(
    Color(0xFF9E3344), // cranberry
    Color(0xFF2E7D55), // fir
    Color(0xFFB08A3E), // old gold
    Color(0xFF4E7A9E), // winter blue
    Color(0xFF7A4E7E), // plum
)
