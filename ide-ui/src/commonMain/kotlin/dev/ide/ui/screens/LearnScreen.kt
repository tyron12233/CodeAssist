package dev.ide.ui.screens

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
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiResumePoint
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

    Box(modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.learn_title), color = Ca.colors.textPrimary, style = Ca.type.large, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(Res.string.learn_subtitle), color = Ca.colors.textSecondary, style = Ca.type.subhead)
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

                if (activeCat == null) {
                    // All: group under per-category subheaders.
                    categories.forEach { cat ->
                        val group = catalog.tracks.filter { it.category == cat }
                        if (group.isNotEmpty()) {
                            CategorySubheader(cat)
                            group.forEachIndexed { i, track -> trackCard(track, i) }
                        }
                    }
                } else {
                    catalog.tracks.filter { it.category == activeCat }.forEachIndexed { i, track -> trackCard(track, i) }
                }
            }

            if (onOpenDocs != null || onJoinDiscord != null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.learn_more_resources), color = Ca.colors.textPrimary, style = Ca.type.title3)
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

private val DiscordBlurple = Color(0xFF5865F2)

private fun accentOf(catalog: UiLearnCatalog, trackId: String): Color? =
    catalog.tracks.firstOrNull { it.id == trackId }?.accentColor?.let { Color(it) }

// ---- resume banner ----

@Composable
private fun ResumeBanner(resume: UiResumePoint, accent: Color?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val base = accent ?: Ca.colors.accent
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
        Text(stringResource(Res.string.learn_continue_learning).uppercase(), color = Color.White.copy(alpha = 0.85f), style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(resume.lessonTitle, color = Color.White, style = Ca.type.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(resume.trackTitle, color = Color.White.copy(alpha = 0.88f), style = Ca.type.footnote)
        }
        ProgressBar(resume.fractionComplete, track = Color.White.copy(alpha = 0.25f), fill = Color.White)
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(CaIcons.play, null, Modifier.size(15.dp), tint = base.darken(0.2f))
            Text(stringResource(Res.string.learn_resume), color = base.darken(0.2f), style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- track cards ----

@Composable
private fun TrackCard(track: UiLearnTrack, lessonsDone: Int, delayMillis: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val accent = track.accentColor?.let { Color(it) } ?: Ca.colors.accent
    val shape = RoundedCornerShape(Ca.radius.lg)
    val total = track.lessons.size
    val fraction = if (total == 0) 0f else lessonsDone.toFloat() / total
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
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
            Text(track.title, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.subtitle, color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressBar(fraction, track = Ca.colors.surface3, fill = accent, modifier = Modifier.weight(1f))
                Text(
                    if (total > 0) stringResource(Res.string.learn_lessons_progress, lessonsDone, total)
                    else pluralStringResource(Res.plurals.learn_lesson_count, total, total),
                    color = Ca.colors.textTertiary, style = Ca.type.caption2,
                )
            }
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
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
        LearnCategoryChip(stringResource(Res.string.learn_all), Ca.colors.accent, CaIcons.grid, selected == null) { onSelect(null) }
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
            .background(if (active) color else Ca.colors.surface2)
            .border(1.dp, if (active) Color.Transparent else Ca.colors.hairline, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (active) Color.White else color)
        Text(
            label,
            color = if (active) Color.White else Ca.colors.textSecondary,
            style = Ca.type.footnote,
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
        Text(category, color = Ca.colors.textPrimary, style = Ca.type.title3)
    }
}

// ---- link cards (docs / community) ----

@Composable
private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, accent: Color? = null) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    val tint = accent ?: Ca.colors.accent
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
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
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(subtitle, color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
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
