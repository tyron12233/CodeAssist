package dev.ide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiStoreSubmission
import dev.ide.ui.backend.UiSubmissionStatus
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair

/**
 * The zero-data Explore state.
 *
 * The rule that governs this file: **shimmer means loading, dashed means empty.** There is no shimmer
 * anywhere here. An animated skeleton on a store that will not fill until somebody publishes is a lie,
 * and it is the single distinction the empty-state handoff calls most important.
 *
 * Nothing says "coming soon" either. The store works — it is simply unstocked — and this state is also
 * what a self-hosted enterprise instance sees permanently until its own team publishes.
 */

/**
 * The one large tonal block on the page.
 *
 * The eyebrow says the store is **open**, deliberately: the failure mode here is a page that reads as
 * broken or unlaunched. It states a fact and offers the action.
 */
@Composable
fun EmptyStoreHero(
    /** True once the user has a submission — the CTA becomes "Publish another". */
    hasSubmission: Boolean,
    onPublish: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 12.dp, bottomStart = 32.dp),
        color = c.primaryContainer,
        contentColor = c.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Box(Modifier.clipToBounds()) {
            Symbol(
                CaSymbols.deployedCode,
                contentDescription = null,
                size = 160.dp,
                tint = c.onPrimaryContainer.copy(alpha = 0.13f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 24.dp, y = 34.dp),
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                Eyebrow("The store is open", color = c.onPrimaryContainer.copy(alpha = 0.75f))
                Text(
                    "No one has published a project yet",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp,
                        letterSpacing = (-0.7).sp,
                    ),
                    color = c.onPrimaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Templates, sample apps and plugins show up here the moment they pass review. " +
                        "Yours can be the first one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onPrimaryContainer.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = 10.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        onClick = onPublish,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 26.dp, bottomEnd = 16.dp, bottomStart = 26.dp),
                        color = c.onPrimaryContainer,
                        contentColor = c.primaryContainer,
                        modifier = Modifier.height(46.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Symbol(CaSymbols.upload, contentDescription = null, size = 20.dp)
                            Text(
                                if (hasSubmission) "Publish another" else "Publish a project",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    Surface(
                        onClick = onGuide,
                        shape = CircleShape,
                        color = Color.Transparent,
                        contentColor = c.onPrimaryContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp, c.onPrimaryContainer),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Publishing guide", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** One step of the publishing flow. */
data class PublishStep(val title: String, val body: String)

/**
 * How publishing works — three steps, entirely in-app.
 *
 * No monospace anywhere and no code blocks: publishing needs no manifest file and no CLI, and showing
 * either would describe a flow that does not exist. Review stays a **human** step, which is the one part
 * the app cannot skip and the copy should keep saying so.
 */
@Composable
fun PublishingSteps(steps: List<PublishStep>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pair = tonalPair(i)
                Box(
                    Modifier.size(30.dp).clip(tileShape(i)).background(pair.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = pair.onContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                    Text(
                        step.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** The default copy for [PublishingSteps]. Kept beside the component so the two cannot drift apart. */
val defaultPublishSteps: List<PublishStep> = listOf(
    PublishStep(
        "Pick a project from Home",
        "Choose any local project. CodeAssist reads its name, language, JDK and Gradle setup for you — " +
            "nothing to write by hand.",
    ),
    PublishStep(
        "Fill in the listing",
        "Title, a short description, category and screenshots — captured from your editor inside the app.",
    ),
    PublishStep(
        "Submit for review",
        "We build it on a clean machine and a human checks licensing and the README. Usually two business days.",
    ),
)

/**
 * The notify switch.
 *
 * The promise in the subtitle is load-bearing: one notification for the first batch, then
 * auto-unsubscribe. Whoever wires this must honour that, or the copy has to change.
 */
@Composable
fun NotifySwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Why the last toggle did not take, if it did not.
     *
     * Needed because the switch only follows the server: without this, a failed subscription looks exactly
     * like a switch that does nothing, which is the complaint that prompted wiring it up in the first place.
     */
    message: String? = null,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Tell me when projects arrive",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSurface,
                )
                Text(
                    message ?: "One notification, only for the first batch.",
                    style = MaterialTheme.typography.bodySmall,
                    // The failure takes over the supporting line rather than adding a row: it replaces the
                    // promise it just failed to keep.
                    color = if (message != null) c.error else c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // A real Switch, not a hand-rolled one: it carries the platform's own semantics and
            // touch target. The prototype draws its own; production should not.
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics {
                    stateDescription = if (checked) "Notifications on" else "Notifications off"
                },
            )
        }
    }
}

/**
 * The submission status card.
 *
 * Sits **above** the hero, because once the user has submitted something their own submission is the most
 * important thing on the page. Each status carries its own line and action — the handoff's full set —
 * since collapsing any two would hide something the submitter needs to act on.
 */
@Composable
fun SubmissionStatusCard(
    submission: UiStoreSubmission,
    onWithdraw: () -> Unit,
    onViewNotes: () -> Unit,
    onViewListing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val (line, actionLabel, action) = when (submission.status) {
        UiSubmissionStatus.SUBMITTED ->
            Triple("In review · usually 2 business days", "Withdraw", onWithdraw)
        UiSubmissionStatus.BUILDING ->
            Triple("Building on a clean machine", "Withdraw", onWithdraw)
        UiSubmissionStatus.CHANGES_REQUESTED ->
            Triple("Changes requested — read the notes", "View notes", onViewNotes)
        UiSubmissionStatus.REJECTED ->
            Triple(
                submission.note?.let { "Not accepted · $it" } ?: "Not accepted",
                "Read why",
                onViewNotes,
            )
        UiSubmissionStatus.PUBLISHED ->
            Triple("Live in the store", "View listing", onViewListing)
    }
    // Only the in-flight states spin; a finished one holding an animation would read as still working.
    val spinning = submission.status == UiSubmissionStatus.SUBMITTED ||
        submission.status == UiSubmissionStatus.BUILDING
    val glyph = when (submission.status) {
        UiSubmissionStatus.PUBLISHED -> CaSymbols.checkCircle
        UiSubmissionStatus.REJECTED -> CaSymbols.error
        UiSubmissionStatus.CHANGES_REQUESTED -> CaSymbols.rateReview
        else -> CaSymbols.hourglassTop
    }
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = c.secondaryContainer,
        contentColor = c.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(c.onSecondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                SpinningGlyph(glyph, spinning, c.secondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    submission.projectName,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = action,
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = c.onSecondaryContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, c.onSecondaryContainer),
                modifier = Modifier.height(34.dp),
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SpinningGlyph(glyph: Char, spinning: Boolean, tint: Color) {
    if (!spinning) {
        Symbol(glyph, contentDescription = null, size = 22.dp, tint = tint)
        return
    }
    val transition = rememberInfiniteTransition(label = "submissionSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "submissionAngle",
    )
    Symbol(glyph, contentDescription = null, size = 22.dp, tint = tint, modifier = Modifier.rotate(angle))
}

/**
 * The ghost-shelf notes for the EMPTY state.
 *
 * Different from the sparse state's on purpose: with nothing published, the honest thing to state is the
 * **condition** that fills the shelf, not a threshold the reader is nowhere near.
 */
fun emptyGhostNote(key: String): String = when (key) {
    "charts" -> "Ranks appear once projects have a week of install history."
    "collections" -> "Curated shelves are assembled by the team from published projects."
    else -> "Recommendations need your install history — install something first."
}
