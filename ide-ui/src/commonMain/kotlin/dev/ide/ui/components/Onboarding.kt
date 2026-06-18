package dev.ide.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * One onboarding step. [hero] is the full-bleed feature mock that fills
 * the 250dp hero region (see [OnboardingMocks]); copy/kicker/icon/cta are fixed, as is the order.
 */
private data class OnboardingStep(
    val kicker: String,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val cta: String,
    val hero: @Composable () -> Unit,
)

private val STEPS: List<OnboardingStep> = listOf(
    OnboardingStep(
        kicker = "On-device",
        icon = CaIcons.layers,
        title = "A real IDE. On your phone.",
        body = "Write, navigate, and build Android & Java projects end to end — no laptop required.",
        cta = "Continue",
        hero = { IdeMock() },
    ),
    OnboardingStep(
        kicker = "Code completion",
        icon = CaIcons.sparkle,
        title = "Completion that knows your code.",
        body = "Eclipse JDT powers precise, ranked suggestions — with signatures and docs — as you type.",
        cta = "Continue",
        hero = { JdtCompletionMock() },
    ),
    OnboardingStep(
        kicker = "Block editing",
        icon = CaIcons.braces,
        title = "Edit as code, or as blocks.",
        body = "Project any Java file into interlocking blocks — a live view of the same source, byte for byte.",
        cta = "Continue",
        hero = { BlocksMock() },
    ),
    OnboardingStep(
        kicker = "Build & run",
        icon = CaIcons.hammer,
        title = "Build real APKs, on device.",
        body = "Resolve, compile, dex, package, sign — then install straight to your phone.",
        cta = "Continue",
        hero = { BuildConsoleMock() },
    ),
    OnboardingStep(
        kicker = "Command palette",
        icon = CaIcons.command,
        title = "Jump anywhere, instantly.",
        body = "One input for commands, files, and symbols — the accelerator behind everything.",
        cta = "Continue",
        hero = { CommandPaletteMock() },
    ),
    OnboardingStep(
        kicker = "Kotlin · Beta",
        icon = CaIcons.code,
        title = "Kotlin completion, now in beta.",
        body = "Full Kotlin code completion, tuned to the same calm, ranked experience as Java.",
        cta = "Continue",
        hero = { KotlinCompletionMock() },
    ),
    OnboardingStep(
        kicker = "XML",
        icon = CaIcons.docText,
        title = "Layouts, fully assisted.",
        body = "Tag, attribute, and resource completion for Android XML — with live validation.",
        cta = "Continue",
        hero = { XmlCompletionMock() },
    ),
    OnboardingStep(
        kicker = "Your files",
        icon = CaIcons.folder,
        title = "Your projects, in a real folder.",
        body = "Projects live in app storage you can open from any file manager — drop in icons, layouts, " +
            "or assets, or edit from a PC. No special permissions, nothing hidden.",
        cta = "Continue",
        hero = { FilesAccessMock() },
    ),
    OnboardingStep(
        kicker = "Jetpack Compose",
        icon = CaIcons.eye,
        title = "Rebuilt on Jetpack Compose.",
        body = "A faster, smoother CodeAssist — native Compose throughout, with live @Preview.",
        cta = "Open a sample project",
        hero = { ComposePreviewMock() },
    ),
)

/**
 * The first-launch feature tour: a glass bottom sheet (~74% on mobile, a
 * centered card on desktop) that pages through the full-bleed feature mocks with a segmented progress bar.
 * The final page's CTA opens the bundled sample via [onOpenSample]; [onFinish] fires once on finish/skip
 * so the host can persist the "seen" flag and dismiss.
 */
@Composable
fun OnboardingSheet(visible: Boolean, onOpenSample: () -> Unit, onFinish: () -> Unit) {
    if (isMobilePlatform) {
        BottomSheet(visible = visible, onDismiss = onFinish, heightFraction = 0.74f) {
            MarqueeBody(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                fillPager = true,
                onOpenSample = onOpenSample,
                onFinish = onFinish,
            )
        }
    } else {
        CenteredDialog(visible = visible, onDismiss = onFinish) {
            val shape = RoundedCornerShape(Ca.radius.sheet)
            Column(
                Modifier
                    .width(400.dp)
                    .clip(shape)
                    .background(Ca.colors.glassThick)
                    .border(1.dp, Ca.colors.glassEdgeTop, shape)
                    .padding(top = 16.dp),
            ) {
                MarqueeBody(
                    modifier = Modifier.fillMaxWidth(),
                    fillPager = false,
                    onOpenSample = onOpenSample,
                    onFinish = onFinish,
                )
            }
        }
    }
}

/**
 * Segmented progress → hero+copy pager → footer. [fillPager] makes the pager fill the remaining height
 * (mobile sheet, where [modifier] is a `weight`); otherwise it's a fixed height (desktop card wraps).
 *
 * Note: predictive/system back-to-previous-page is intentionally not wired here — `BackHandler` is
 * Android-only and this is shared commonMain code; the pager's own swipe covers going back.
 */
@Composable
private fun MarqueeBody(
    modifier: Modifier,
    fillPager: Boolean,
    onOpenSample: () -> Unit,
    onFinish: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { STEPS.size })
    val page = pager.currentPage
    val last = page == STEPS.lastIndex
    val step = STEPS[page]

    Column(modifier) {
        SegmentedProgress(
            count = STEPS.size,
            current = page,
            onJump = { i -> scope.launch { pager.animateScrollToPage(i) } },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        )

        val pagerMod = (if (fillPager) Modifier.weight(1f) else Modifier.height(404.dp)).fillMaxWidth()
        HorizontalPager(state = pager, modifier = pagerMod, verticalAlignment = Alignment.Top) { i ->
            val s = STEPS[i]
            val active = i == page
            Column(Modifier.fillMaxSize()) {
                Hero(kicker = s.kicker, icon = s.icon, active = active, content = s.hero)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) { StepCopy(title = s.title, body = s.body, active = active) }
            }
        }

        Footer(
            ctaLabel = step.cta,
            showArrow = last,
            onPrimary = {
                if (last) { onOpenSample(); onFinish() } else scope.launch { pager.animateScrollToPage(page + 1) }
            },
            skipLabel = if (last) "Maybe later" else "Skip for now",
            onSkip = onFinish,
        )
    }
}

/** 8 weighted segments; filled up to and including [current], tappable to jump, color-animated. */
@Composable
private fun SegmentedProgress(count: Int, current: Int, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            val color by animateColorAsState(if (i <= current) Ca.colors.accent else Ca.colors.surface3, tween(Motion.BASE), label = "seg$i")
            Box(
                Modifier
                    .weight(1f)
                    .height(18.dp) // a comfortable tap target; the visible bar stays 4dp
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onJump(i) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(color))
            }
        }
    }
}

/** 250dp full-bleed hero: the feature mock + a bottom fade into the sheet + the overlaid kicker chip. */
@Composable
private fun Hero(kicker: String, icon: ImageVector, active: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(250.dp).padding(top = 14.dp).clipToBounds()) {
        // ca-pop: scale 0.96 → 1, translateY 5 → 0 (transform-only; visible at rest if the clock is frozen).
        val pop by animateFloatAsState(if (active) 1f else 0f, tween(Motion.BASE, easing = Motion.spring), label = "pop")
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val sc = 0.96f + 0.04f * pop
                scaleX = sc; scaleY = sc
                translationY = (1f - pop) * 5.dp.toPx()
            },
        ) { content() }
        // Bottom fade blends the opaque mock into the glass sheet surface.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ca.colors.glassThick))),
        )
        KickerChip(kicker, icon, Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 12.dp))
    }
}

@Composable
private fun KickerChip(text: String, icon: ImageVector, modifier: Modifier) {
    Row(
        modifier
            .height(26.dp)
            .clip(RoundedCornerShape(Ca.radius.pill))
            .background(Ca.colors.glassThick)
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.pill))
            .padding(start = 9.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = Ca.colors.accent)
        Text(text, color = Ca.colors.accent, fontFamily = Ca.type.uiFamily, fontSize = 11.5f.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StepCopy(title: String, body: String, active: Boolean) {
    // ca-fade-up: translateY 9 → 0 (transform-only).
    val up by animateFloatAsState(if (active) 1f else 0f, tween(Motion.BASE, easing = Motion.quiet), label = "fadeUp")
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp)
            .graphicsLayer { translationY = (1f - up) * 9.dp.toPx() },
    ) {
        Text(
            title,
            color = Ca.colors.textPrimary,
            fontFamily = Ca.type.uiFamily,
            fontSize = 29.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            style = Ca.type.title1.copy(lineBreak = LineBreak.Heading),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            color = Ca.colors.textSecondary,
            fontFamily = Ca.type.uiFamily,
            fontSize = 15.5f.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Normal,
            style = Ca.type.body.copy(lineBreak = LineBreak.Paragraph),
        )
    }
}

@Composable
private fun ColumnScope.Footer(
    ctaLabel: String,
    showArrow: Boolean,
    onPrimary: () -> Unit,
    skipLabel: String,
    onSkip: () -> Unit,
) {
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomPad = with(density) { maxOf(20.dp, navBottom.toDp()) }
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp).padding(bottom = bottomPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimaryCta(label = ctaLabel, showArrow = showArrow, onClick = onPrimary)
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSkip),
            contentAlignment = Alignment.Center,
        ) {
            Text(skipLabel, color = Ca.colors.textTertiary, fontFamily = Ca.type.uiFamily, fontSize = 13.5f.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Full-width accent CTA, 52dp, with spring press-scale and an optional trailing arrow on the last page. */
@Composable
private fun PrimaryCta(label: String, showArrow: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .height(52.dp)
            .clip(RoundedCornerShape(Ca.radius.control))
            .background(Ca.colors.accent)
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ca.colors.textOnAccent, fontFamily = Ca.type.uiFamily, fontSize = 16.5f.sp, fontWeight = FontWeight.SemiBold)
        if (showArrow) Icon(CaIcons.arrowRight, null, Modifier.size(19.dp), tint = Ca.colors.textOnAccent)
    }
}
