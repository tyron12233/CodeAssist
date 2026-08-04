package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiResumePoint
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.Chip
import dev.ide.ui.components.darken
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.join_the_community
import dev.ide.ui.generated.resources.learn_community_content
import dev.ide.ui.generated.resources.learn_continue_learning
import dev.ide.ui.generated.resources.learn_documentation
import dev.ide.ui.generated.resources.learn_documentation_content
import dev.ide.ui.generated.resources.learn_lesson_count
import dev.ide.ui.generated.resources.learn_lessons_progress
import dev.ide.ui.generated.resources.learn_more_resources
import dev.ide.ui.generated.resources.learn_resume
import dev.ide.ui.generated.resources.learn_subtitle
import dev.ide.ui.generated.resources.learn_title
import dev.ide.ui.generated.resources.learn_all
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The home screen's Learn tab: a progress-aware hub for the interactive lessons. A **Resume** banner (shown
 * when there's a lesson in progress) jumps back to where the learner left off; below it, the lesson **tracks**
 * (Kotlin Basics, Java Basics, …) each show their completion, and a small **More** section links out to the
 * docs and community. All content comes from [IdeBackend.learn]; progress is re-read on [epoch] changes (bumped
 * when the learner returns from a lesson).
 */
@Composable
fun LearnScreen(
    backend: IdeBackend,
    onOpenTrack: (trackId: String) -> Unit,
    onResume: (trackId: String, lessonId: String, stepIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    epoch: Int = 0,
    onOpenDocs: (() -> Unit)? = null,
    onJoinDiscord: (() -> Unit)? = null,
) {
    val catalog by produceState(UiLearnCatalog(), backend, epoch) {
        value = runCatching { backend.learn.catalog() }.getOrDefault(UiLearnCatalog())
    }
    val progress = remember(backend, epoch) { runCatching { backend.learn.progress() }.getOrNull() }
    val resume = remember(backend, epoch) { runCatching { backend.learn.resume() }.getOrNull() }
    var selectedCat by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.learn_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(Res.string.learn_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }

            if (resume != null) {
                Spacer(Modifier.height(2.dp))
                ResumeBanner(resume, accentOf(catalog, resume.trackId)) {
                    onResume(resume.trackId, resume.lessonId, resume.stepIndex)
                }
            }

            if (catalog.tracks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val categories = remember(catalog) { orderedCategories(catalog.tracks) }
                // Keep the selected category valid if the catalog changes.
                val activeCat = selectedCat?.takeIf { it in categories }
                LearnCategoryStrip(categories, activeCat) { selectedCat = it }

                @Composable
                fun trackCard(track: UiLearnTrack, index: Int) {
                    val done = track.lessons.count { (progress?.completedByLesson?.get(it.id)?.size ?: 0) >= it.stepCount }
                    TrackCard(track, done, delayMillis = index * 40) { onOpenTrack(track.id) }
                }

                // Cap interleaved ads so a long track list can't fill up with them (the trailing slot below is
                // separate, so the tab shows at most MAX_INTERLEAVED_ADS + 1). Counted across this composition pass.
                var interleavedAds = 0
                if (activeCat == null) {
                    // All: group under per-category subheaders, with a native ad between each section (browse
                    // time between topics). Skip the last group — the trailing slot already follows it.
                    categories.forEachIndexed { ci, cat ->
                        val group = catalog.tracks.filter { it.category == cat }
                        if (group.isNotEmpty()) {
                            CategorySubheader(cat)
                            group.forEachIndexed { i, track -> trackCard(track, i) }
                            if (ci < categories.lastIndex && interleavedAds < MAX_INTERLEAVED_ADS) {
                                AdSlot(AdPlacement.LEARN); interleavedAds++
                            }
                        }
                    }
                } else {
                    // Single category: interleave a native ad every few cards as the learner scrolls (not as the
                    // very last item — the trailing slot follows).
                    val filtered = catalog.tracks.filter { it.category == activeCat }
                    filtered.forEachIndexed { i, track ->
                        trackCard(track, i)
                        if ((i + 1) % 3 == 0 && i < filtered.lastIndex && interleavedAds < MAX_INTERLEAVED_ADS) {
                            AdSlot(AdPlacement.LEARN); interleavedAds++
                        }
                    }
                }
            }

            // A native ad between the lesson tracks and the resource links — browse time, not lesson time.
            AdSlot(AdPlacement.LEARN)

            if (onOpenDocs != null || onJoinDiscord != null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.learn_more_resources), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                if (onOpenDocs != null) {
                    LinkCard(CaIcons.docText, stringResource(Res.string.learn_documentation), stringResource(Res.string.learn_documentation_content), onClick = onOpenDocs)
                }
                if (onJoinDiscord != null) {
                    LinkCard(CaIcons.discord, stringResource(Res.string.join_the_community), stringResource(Res.string.learn_community_content), accent = DiscordBlurple, onClick = onJoinDiscord)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** How many ads may be interleaved among the Learn tracks (the trailing slot below the list is separate). */
private const val MAX_INTERLEAVED_ADS = 2

private val DiscordBlurple = Color(0xFF5865F2)

private fun accentOf(catalog: UiLearnCatalog, trackId: String): Color? =
    catalog.tracks.firstOrNull { it.id == trackId }?.accentColor?.let { Color(it) }

// ---- resume banner ----

@Composable
private fun ResumeBanner(resume: UiResumePoint, accent: Color?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val base = accent ?: MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(Ca.radius.xl)
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .background(Brush.linearGradient(listOf(base, base.darken(0.55f))))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.learn_continue_learning).uppercase(), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(resume.lessonTitle, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(resume.trackTitle, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
        }
        ProgressBar(resume.fractionComplete, track = Color.White.copy(alpha = 0.25f), fill = Color.White)
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(CaIcons.play, null, Modifier.size(15.dp), tint = base.darken(0.2f))
            Text(stringResource(Res.string.learn_resume), color = base.darken(0.2f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- track cards ----

@Composable
private fun TrackCard(track: UiLearnTrack, lessonsDone: Int, delayMillis: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val accent = track.accentColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(Ca.radius.lg)
    val total = track.lessons.size
    val fraction = if (total == 0) 0f else lessonsDone.toFloat() / total
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(Ca.radius.md)).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(learnCategoryIcon(track.category), null, Modifier.size(24.dp), tint = accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(track.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressBar(fraction, track = MaterialTheme.colorScheme.surfaceContainerHighest, fill = accent, modifier = Modifier.weight(1f))
                Text(
                    if (total > 0) stringResource(Res.string.learn_lessons_progress, lessonsDone, total)
                    else pluralStringResource(Res.plurals.learn_lesson_count, total, total),
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

// ---- categories ----

/** Distinct track categories in a friendly order (known ones first, then any extras). */
private fun orderedCategories(tracks: List<UiLearnTrack>): List<String> {
    val order = listOf("Kotlin", "Compose", "Java", "Android", "Get started")
    return tracks.map { it.category }.distinct()
        .sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
}

private fun learnCategoryColor(category: String): Color = when (category.lowercase()) {
    "kotlin" -> Color(0xFF7F52FF)
    "compose" -> Color(0xFF10A5A8)
    "java" -> Color(0xFFF89820)
    "android" -> Color(0xFF3DDC84)
    "get started", "getting started", "general" -> Color(0xFF3FBDD9)
    else -> Color(0xFF8E8E93)
}

private fun learnCategoryIcon(category: String): ImageVector = when (category.lowercase()) {
    "kotlin" -> CaIcons.code
    "compose" -> CaIcons.layers
    "java" -> CaIcons.braces
    "android" -> CaIcons.androidLogo
    "get started", "getting started", "general" -> CaIcons.sparkle
    else -> CaIcons.lightbulb
}

@Composable
private fun LearnCategoryStrip(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LearnCategoryChip(stringResource(Res.string.learn_all), MaterialTheme.colorScheme.primary, CaIcons.grid, selected == null) { onSelect(null) }
        categories.forEach { c ->
            LearnCategoryChip(c, learnCategoryColor(c), learnCategoryIcon(c), selected == c) {
                onSelect(if (selected == c) null else c)
            }
        }
    }
}

@Composable
private fun LearnCategoryChip(label: String, color: Color, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier
            .pressScale(interaction)
            .clip(shape)
            .background(if (active) color else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (active) Color.White else color)
        Text(
            label,
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun CategorySubheader(category: String) {
    Row(
        Modifier.padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(learnCategoryColor(category)))
        Text(category, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
    }
}

// ---- link cards (docs / community) ----

@Composable
private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, accent: Color? = null) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    val tint = accent ?: MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(Ca.radius.md)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** A slim rounded progress bar whose fill animates to [fraction] (clamped to 0..1). */
@Composable
fun ProgressBar(fraction: Float, track: Color, fill: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        animationSpec = tween(Motion.BASE, easing = Motion.soft),
        label = "progress",
    )
    Box(
        modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(track),
    ) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(fill))
    }
}
