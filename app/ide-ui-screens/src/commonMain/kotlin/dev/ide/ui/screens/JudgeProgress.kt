package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiJudgeProgress
import dev.ide.ui.backend.UiJudgeStep
import dev.ide.ui.backend.UiJudgeStepState
import dev.ide.ui.backend.UiJudgeStepStatus
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.challenge_judging
import dev.ide.ui.generated.resources.judge_detail_attempt
import dev.ide.ui.generated.resources.judge_detail_compile_failed
import dev.ide.ui.generated.resources.judge_detail_compile_running
import dev.ide.ui.generated.resources.judge_detail_compiled
import dev.ide.ui.generated.resources.judge_detail_counting_jvm
import dev.ide.ui.generated.resources.judge_detail_jvm_ready
import dev.ide.ui.generated.resources.judge_detail_jvm_starting
import dev.ide.ui.generated.resources.judge_detail_measure_failed
import dev.ide.ui.generated.resources.judge_detail_measured
import dev.ide.ui.generated.resources.judge_detail_practice
import dev.ide.ui.generated.resources.judge_detail_saved
import dev.ide.ui.generated.resources.judge_detail_size
import dev.ide.ui.generated.resources.judge_detail_tests_done
import dev.ide.ui.generated.resources.judge_detail_tests_failed
import dev.ide.ui.generated.resources.judge_detail_tests_loaded
import dev.ide.ui.generated.resources.judge_detail_timing
import dev.ide.ui.generated.resources.judge_detail_vm
import dev.ide.ui.generated.resources.judge_hide
import dev.ide.ui.generated.resources.judge_not_live
import dev.ide.ui.generated.resources.judge_short_boot
import dev.ide.ui.generated.resources.judge_short_compile
import dev.ide.ui.generated.resources.judge_short_measure
import dev.ide.ui.generated.resources.judge_short_record
import dev.ide.ui.generated.resources.judge_short_tests
import dev.ide.ui.generated.resources.judge_step_boot
import dev.ide.ui.generated.resources.judge_step_claim
import dev.ide.ui.generated.resources.judge_step_compile
import dev.ide.ui.generated.resources.judge_step_load
import dev.ide.ui.generated.resources.judge_step_measure
import dev.ide.ui.generated.resources.judge_step_record
import dev.ide.ui.generated.resources.judge_step_tests
import dev.ide.ui.generated.resources.judge_steps_title
import dev.ide.ui.generated.resources.judge_subtitle
import dev.ide.ui.generated.resources.judge_title
import dev.ide.ui.generated.resources.judge_total
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.compose.resources.stringResource

/**
 * The judge at work, step by step.
 *
 * A submission takes ten seconds or more (a VM boots, a compiler starts, the hidden tests run, then the
 * solution is measured at several sizes), and a spinner for that long reads as "stuck". This shows each
 * step as the judge reports it, with its real duration, so the wait has a shape: which step is running,
 * what it has found so far, and how much is left.
 *
 * Hiding the sheet does not stop anything; the submit button keeps naming the current step and opens it again.
 */
@Composable
fun JudgeProgressSheet(progress: UiJudgeProgress?, visible: Boolean, onHide: () -> Unit) {
    BottomSheet(visible = visible && progress != null, onDismiss = onHide, heightFraction = 0.72f) {
        val p = progress ?: return@BottomSheet
        val now = rememberFrameClock(100)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.judge_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(Res.string.judge_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        formatDuration(max(0L, now - p.startedAtMs)),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            val fraction by animateFloatAsState(
                targetValue = if (p.live) overallFraction(p, now) else 0f,
                animationSpec = tween(400),
            )
            if (p.live) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Ca.radius.pill)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Ca.radius.pill)),
                )
                Text(
                    stringResource(Res.string.judge_not_live),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            JudgeTimeline(p, now = now, hideUnreached = false)

            TextButton(onClick = onHide, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.judge_hide))
            }
        }
    }
}

/**
 * The finished judgement's steps, folded under the verdict: what ran, for how long, and what each found.
 * Collapsed by default, because the verdict is the news and this is the receipt.
 */
@Composable
fun JudgeReceipt(progress: UiJudgeProgress, modifier: Modifier = Modifier, initiallyOpen: Boolean = false) {
    if (!progress.live) return
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val total = progress.steps.sumOf { it.ms ?: 0L }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(CaSymbols.dns, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.judge_steps_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.judge_total, formatDuration(total)),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val turn by animateFloatAsState(if (open) 180f else 0f, tween(200))
            Symbol(
                CaSymbols.expandMore,
                contentDescription = null,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(turn),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                JudgeTimeline(progress, now = 0L, hideUnreached = true)
            }
        }
    }
}

/** What the submit button says while a judgement runs: the current step, briefly. */
@Composable
fun judgeShortLabel(progress: UiJudgeProgress?): String {
    val running = progress?.takeIf { it.live && !it.finished }?.steps?.lastOrNull { it.state == UiJudgeStepState.Running }
    return stringResource(
        when (running?.step) {
            UiJudgeStep.Boot -> Res.string.judge_short_boot
            UiJudgeStep.Compile -> Res.string.judge_short_compile
            UiJudgeStep.Tests -> Res.string.judge_short_tests
            UiJudgeStep.Measure -> Res.string.judge_short_measure
            UiJudgeStep.Record -> Res.string.judge_short_record
            else -> Res.string.challenge_judging
        },
    )
}

@Composable
private fun JudgeTimeline(progress: UiJudgeProgress, now: Long, hideUnreached: Boolean) {
    val rows = progress.steps.filter { !hideUnreached || it.state != UiJudgeStepState.Waiting }
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, status ->
            StepRow(
                status = status,
                language = progress.language,
                live = progress.live,
                now = now,
                last = i == rows.lastIndex,
            )
        }
    }
}

@Composable
private fun StepRow(status: UiJudgeStepStatus, language: UiChallengeLanguage, live: Boolean, now: Long, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val state = if (live) status.state else UiJudgeStepState.Waiting
    val accent by animateColorAsState(
        when (state) {
            UiJudgeStepState.Done -> scheme.primary
            UiJudgeStepState.Failed -> scheme.error
            UiJudgeStepState.Running -> scheme.primary
            UiJudgeStepState.Waiting -> scheme.outlineVariant
        },
        tween(250),
    )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The rail: a marker per step and the line that joins it to the next.
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMarker(status.step, state, accent)
            if (!last) {
                Box(
                    Modifier.width(2.dp).weight(1f).padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (state == UiJudgeStepState.Done) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp).heightIn(min = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stepTitle(status.step, language),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (state == UiJudgeStepState.Running) FontWeight.Bold else FontWeight.Medium,
                    color = if (state == UiJudgeStepState.Waiting) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 3.dp),
                )
                val duration = when (state) {
                    UiJudgeStepState.Done, UiJudgeStepState.Failed -> status.ms
                    UiJudgeStepState.Running -> status.startedAtMs?.let { if (now > 0) max(0L, now - it) else null }
                    UiJudgeStepState.Waiting -> null
                }
                if (duration != null) {
                    Text(
                        formatDuration(duration),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (state == UiJudgeStepState.Running) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
            stepDetail(status, state)?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == UiJudgeStepState.Failed) scheme.error else scheme.onSurfaceVariant,
                )
            }
            if (status.step == UiJudgeStep.Measure && state != UiJudgeStepState.Waiting) MeasureBars(status, accent)
        }
    }
}

@Composable
private fun StepMarker(step: UiJudgeStep, state: UiJudgeStepState, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(
            when (state) {
                UiJudgeStepState.Done -> accent
                UiJudgeStepState.Failed -> accent
                UiJudgeStepState.Running -> scheme.primaryContainer
                UiJudgeStepState.Waiting -> scheme.surfaceContainerHigh
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            UiJudgeStepState.Done -> Symbol(CaSymbols.check, contentDescription = null, size = 16.dp, tint = scheme.onPrimary)
            UiJudgeStepState.Failed -> Symbol(CaSymbols.close, contentDescription = null, size = 16.dp, tint = scheme.onError)
            UiJudgeStepState.Running -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = scheme.onPrimaryContainer,
            )
            UiJudgeStepState.Waiting -> Symbol(stepGlyph(step), contentDescription = null, size = 15.dp, tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * The sizes measured so far, as bars on a log scale: a solution that scales well climbs in even steps, and
 * one that does not shows its last bars leaping. The same picture the verdict's growth chart draws, while
 * it is still being measured.
 */
@Composable
private fun MeasureBars(status: UiJudgeStepStatus, accent: Color) {
    val ops = status.detail["opsSeries"].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    val of = status.detail["of"]?.toIntOrNull() ?: status.detail["sizes"]?.toIntOrNull() ?: 0
    if (of <= 0 || status.detail["counted"] == "false") return
    val top = ln(max(2L, ops.maxOrNull() ?: 2L).toDouble())
    val bottom = ln(max(1L, ops.minOrNull() ?: 1L).toDouble()) - 1.0
    Row(
        Modifier.padding(top = 8.dp).height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until of) {
            val v = ops.getOrNull(i)
            val h = if (v == null) 0.12f else {
                val f = ((ln(max(1L, v).toDouble()) - bottom) / max(1e-6, top - bottom)).toFloat()
                min(1f, max(0.18f, f))
            }
            val height by animateFloatAsState(h, tween(350))
            Box(
                Modifier.width(14.dp).fillMaxHeight(height).clip(RoundedCornerShape(3.dp))
                    .background(if (v == null) MaterialTheme.colorScheme.surfaceContainerHighest else accent.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
private fun stepTitle(step: UiJudgeStep, language: UiChallengeLanguage): String = when (step) {
    UiJudgeStep.Claim -> stringResource(Res.string.judge_step_claim)
    UiJudgeStep.Load -> stringResource(Res.string.judge_step_load)
    UiJudgeStep.Boot -> stringResource(Res.string.judge_step_boot)
    UiJudgeStep.Compile -> stringResource(Res.string.judge_step_compile, if (language == UiChallengeLanguage.Kotlin) "kotlinc" else "javac")
    UiJudgeStep.Tests -> stringResource(Res.string.judge_step_tests)
    UiJudgeStep.Measure -> stringResource(Res.string.judge_step_measure)
    UiJudgeStep.Record -> stringResource(Res.string.judge_step_record)
}

private fun stepGlyph(step: UiJudgeStep) = when (step) {
    UiJudgeStep.Claim -> CaSymbols.verified
    UiJudgeStep.Load -> CaSymbols.cloudDownload
    UiJudgeStep.Boot -> CaSymbols.dns
    UiJudgeStep.Compile -> CaSymbols.terminal
    UiJudgeStep.Tests -> CaSymbols.playArrow
    UiJudgeStep.Measure -> CaSymbols.functions
    UiJudgeStep.Record -> CaSymbols.storage
}

/** The line under a step's title: what it is doing now, or what it found. */
@Composable
private fun stepDetail(status: UiJudgeStepStatus, state: UiJudgeStepState): String? {
    val d = status.detail
    fun int(key: String) = d[key]?.toIntOrNull()
    fun long(key: String) = d[key]?.toLongOrNull()
    if (state == UiJudgeStepState.Waiting) return null
    return when (status.step) {
        UiJudgeStep.Claim -> when {
            d["practice"] == "true" -> stringResource(Res.string.judge_detail_practice)
            int("attempt") != null && state == UiJudgeStepState.Done -> stringResource(Res.string.judge_detail_attempt, int("attempt")!!)
            else -> null
        }
        UiJudgeStep.Load -> int("tests")?.let { stringResource(Res.string.judge_detail_tests_loaded, it) }
        UiJudgeStep.Boot -> int("vcpus")?.let { stringResource(Res.string.judge_detail_vm, it) }
        UiJudgeStep.Compile -> when (state) {
            UiJudgeStepState.Running -> stringResource(Res.string.judge_detail_compile_running)
            UiJudgeStepState.Failed -> stringResource(Res.string.judge_detail_compile_failed)
            else -> long("compilerMs")?.let { stringResource(Res.string.judge_detail_compiled, formatDuration(it)) }
        }
        UiJudgeStep.Tests -> {
            val total = int("total") ?: 0
            val passed = int("passed")
            when {
                passed != null && state == UiJudgeStepState.Failed && passed < total -> stringResource(Res.string.judge_detail_tests_done, passed, total)
                state == UiJudgeStepState.Failed -> stringResource(Res.string.judge_detail_tests_failed)
                passed != null -> stringResource(Res.string.judge_detail_tests_done, passed, total)
                long("jvmReadyMs") != null -> stringResource(Res.string.judge_detail_jvm_ready, formatDuration(long("jvmReadyMs")!!), total)
                else -> stringResource(Res.string.judge_detail_jvm_starting)
            }
        }
        UiJudgeStep.Measure -> {
            val n = long("n")
            when {
                state == UiJudgeStepState.Failed -> stringResource(Res.string.judge_detail_measure_failed)
                state == UiJudgeStepState.Done && int("measured") != null -> stringResource(Res.string.judge_detail_measured, int("measured")!!)
                d["counted"] == "false" -> stringResource(Res.string.judge_detail_timing)
                n != null -> stringResource(
                    Res.string.judge_detail_size,
                    groupThousands(n),
                    int("index") ?: 0,
                    int("of") ?: 0,
                    compactCount(long("ops") ?: 0L),
                )
                else -> stringResource(Res.string.judge_detail_counting_jvm)
            }
        }
        UiJudgeStep.Record -> if (state == UiJudgeStepState.Done) stringResource(Res.string.judge_detail_saved) else null
    }
}

/**
 * How far along the whole judgement is, for the bar under the title. Each step counts for what it usually
 * takes, and a running step fills toward (never past) nine tenths of its share until it reports done, so the
 * bar keeps moving through a long compile without promising it is nearly over.
 */
private fun overallFraction(p: UiJudgeProgress, now: Long): Float {
    fun expected(step: UiJudgeStep): Long = when (step) {
        UiJudgeStep.Claim -> 300
        UiJudgeStep.Load -> 600
        UiJudgeStep.Boot -> 2_300
        UiJudgeStep.Compile -> if (p.language == UiChallengeLanguage.Kotlin) 6_500 else 1_300
        UiJudgeStep.Tests -> 1_100
        UiJudgeStep.Measure -> 4_500
        UiJudgeStep.Record -> 500
    }
    val all = UiJudgeStep.entries.sumOf { expected(it) }.toFloat()
    var done = 0f
    for (s in p.steps) {
        val share = expected(s.step)
        done += when (s.state) {
            UiJudgeStepState.Done, UiJudgeStepState.Failed -> share.toFloat()
            UiJudgeStepState.Running -> {
                val index = s.detail["index"]?.toFloatOrNull()
                val of = s.detail["of"]?.toFloatOrNull()
                val f = if (index != null && of != null && of > 0) {
                    0.6f + 0.4f * index / of
                } else {
                    val elapsed = s.startedAtMs?.let { max(0L, now - it) } ?: 0L
                    min(0.9f, elapsed.toFloat() / share)
                }
                share * min(0.95f, f)
            }
            UiJudgeStepState.Waiting -> 0f
        }
    }
    return if (p.finished) 1f else min(0.99f, done / all)
}

/** A clock for running timers, ticking every [stepMs] while on screen (frame driven, like [rememberSecondsTicker]). */
@Composable
private fun rememberFrameClock(stepMs: Long): Long {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(stepMs) {
        var last = 0L
        while (true) {
            withFrameMillis { frame ->
                if (frame - last >= stepMs) {
                    last = frame
                    now = nowMillis()
                }
            }
        }
    }
    return now
}

/** 706 ms, 6.4 s, 1:12. */
internal fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> "${ms / 1_000}.${(ms % 1_000) / 100} s"
    else -> "${ms / 60_000}:${((ms / 1_000) % 60).toString().padStart(2, '0')}"
}

/** 32000 as 32,000. */
internal fun groupThousands(n: Long): String {
    val digits = kotlin.math.abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** 9979894 as 10.0M; 320561 as 320.6K. */
internal fun compactCount(n: Long): String = when {
    n >= 1_000_000_000 -> oneDecimal(n / 1e9) + "B"
    n >= 1_000_000 -> oneDecimal(n / 1e6) + "M"
    n >= 1_000 -> oneDecimal(n / 1e3) + "K"
    else -> n.toString()
}

private fun oneDecimal(v: Double): String {
    val tenths = kotlin.math.round(v * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
