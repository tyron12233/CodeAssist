package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiJudgeProgress
import dev.ide.ui.backend.UiJudgeStep
import dev.ide.ui.backend.UiJudgeStepState
import dev.ide.ui.backend.UiJudgeStepStatus
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The judge's live progress sheet mid-compile and mid-measure, and the folded receipt under a verdict. Not an assertion. */
class JudgeProgressSnapshot {

    @Test
    fun renderJudgeProgress() {
        val now = System.currentTimeMillis()
        fun done(step: UiJudgeStep, ms: Long, vararg detail: Pair<String, String>) =
            UiJudgeStepStatus(step, UiJudgeStepState.Done, ms = ms, detail = detail.toMap())
        val head = listOf(
            done(UiJudgeStep.Claim, 12, "attempt" to "2", "practice" to "false"),
            done(UiJudgeStep.Load, 434, "tests" to "13"),
            done(UiJudgeStep.Boot, 2212, "vcpus" to "2"),
        )
        val compiling = UiJudgeProgress(
            language = UiChallengeLanguage.Kotlin,
            startedAtMs = now - 7_400,
            live = true,
            steps = head + listOf(
                UiJudgeStepStatus(UiJudgeStep.Compile, UiJudgeStepState.Running, startedAtMs = now - 4_700, detail = mapOf("language" to "kotlin")),
                UiJudgeStepStatus(UiJudgeStep.Tests),
                UiJudgeStepStatus(UiJudgeStep.Measure),
                UiJudgeStepStatus(UiJudgeStep.Record),
            ),
        )
        val measuring = compiling.copy(
            startedAtMs = now - 16_900,
            steps = head + listOf(
                done(UiJudgeStep.Compile, 6875, "compilerMs" to "6418"),
                done(UiJudgeStep.Tests, 1119, "total" to "13", "passed" to "13", "jvmReadyMs" to "706"),
                UiJudgeStepStatus(
                    UiJudgeStep.Measure, UiJudgeStepState.Running, startedAtMs = now - 3_900,
                    detail = mapOf(
                        "sizes" to "6", "counted" to "true", "n" to "8000", "ops" to "2569464", "index" to "4", "of" to "6",
                        "opsSeries" to "320561,641918,1284483,2569464",
                    ),
                ),
                UiJudgeStepStatus(UiJudgeStep.Record),
            ),
        )
        val finished = measuring.copy(
            finished = true,
            steps = measuring.steps.map {
                when (it.step) {
                    UiJudgeStep.Measure -> it.copy(
                        state = UiJudgeStepState.Done, ms = 5558,
                        detail = it.detail + ("measured" to "6") + ("opsSeries" to "320561,641918,1284483,2569464,5139277,9979894"),
                    )
                    UiJudgeStep.Record -> done(UiJudgeStep.Record, 380)
                    else -> it
                }
            },
        )
        val failed = compiling.copy(
            finished = true,
            steps = head + listOf(
                done(UiJudgeStep.Compile, 1243, "compilerMs" to "1100"),
                UiJudgeStepStatus(UiJudgeStep.Tests, UiJudgeStepState.Failed, ms = 902, detail = mapOf("total" to "13", "passed" to "9")),
                UiJudgeStepStatus(UiJudgeStep.Measure),
                done(UiJudgeStep.Record, 300),
            ),
            language = UiChallengeLanguage.Java,
        )

        val out = System.getenv("SNAPSHOT_DIR") ?: "build/snapshots"
        File(out).mkdirs()
        for (dark in listOf(true, false)) {
            val tag = if (dark) "dark" else "light"
            render("$out/judge-compiling-$tag.png", dark) { JudgeProgressSheet(compiling, visible = true, onHide = {}) }
            render("$out/judge-measuring-$tag.png", dark) { JudgeProgressSheet(measuring, visible = true, onHide = {}) }
            render("$out/judge-receipt-$tag.png", dark) {
                Box(Modifier.padding(20.dp)) { JudgeReceipt(finished, initiallyOpen = true) }
            }
            render("$out/judge-receipt-failed-$tag.png", dark) {
                Box(Modifier.padding(20.dp)) { JudgeReceipt(failed, initiallyOpen = true) }
            }
        }
    }

    private fun render(path: String, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
        try {
            // Step the clock through the sheet's entrance so it settles where a person would see it.
            var image = scene.render(0L)
            for (f in 1..60) image = scene.render(f * 16_000_000L)
            File(path).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
