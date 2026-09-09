package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.ChallengeService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeBoard
import dev.ide.ui.backend.UiChallengeDay
import dev.ide.ui.backend.UiChallengeHistoryEntry
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiChallengeMaterial
import dev.ide.ui.backend.UiChallengeProblem
import dev.ide.ui.backend.UiChallengeProfile
import dev.ide.ui.backend.UiChallengeProgress
import dev.ide.ui.backend.UiChallengeRank
import dev.ide.ui.backend.UiChallengeSample
import dev.ide.ui.backend.UiChallengeStreak
import dev.ide.ui.backend.UiGrowthPoint
import dev.ide.ui.theme.CodeAssistTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The Challenges tab and the growth chart, rendered off-screen.
 *
 * The chart is the part worth a picture: it plots on log axes so a power law reads as a straight line,
 * and that is the sort of thing that looks right in code and wrong on screen. Both signed-in and
 * signed-out states are rendered, because the signed-out one is the whole argument for signing in and it
 * is the easier of the two to break without noticing.
 */
class ChallengeSnapshot {

    private val problem = UiChallengeProblem(
        id = "p1",
        slug = "two-sum",
        title = "Two Sum",
        difficulty = "easy",
        statement = "Return the indices of the two numbers that add up to the target.",
        tags = listOf("array", "hash map"),
        featuredLanguage = UiChallengeLanguage.Kotlin,
        materials = mapOf(
            UiChallengeLanguage.Kotlin to UiChallengeMaterial(
                language = UiChallengeLanguage.Kotlin,
                stub = "fun twoSum(nums: IntArray, target: Int): IntArray {\n    TODO()\n}",
                harness = "{{USER_CODE}}",
                functionName = "twoSum",
                returnType = "int[]",
                parameters = listOf("nums" to "int[]", "target" to "int"),
            ),
        ),
        samples = listOf(UiChallengeSample(0, "[[2,7,11,15],9]", "[0,1]", "2 + 7 = 9")),
    )

    private val board = UiChallengeBoard(
        date = "2026-09-07",
        total = 3,
        rows = listOf(
            rank(1, "tyron", "Tyron", 148, "O(n)", 12_400, true),
            rank(2, "hana", "Hana", 131, "O(n)", 19_800, false),
            rank(3, "mikko", "Mikko", 96, "O(n log n)", 31_200, false),
        ),
    )

    private fun rank(
        at: Int,
        handle: String,
        name: String,
        score: Int,
        klass: String,
        ns: Long,
        isYou: Boolean,
    ) = UiChallengeRank(
        rank = at,
        handle = handle,
        displayName = name,
        score = score,
        language = UiChallengeLanguage.Kotlin,
        timeClass = klass,
        spaceClass = "O(n)",
        runtimeNs = ns,
        allocBytes = 128_040,
        solveMs = 420_000,
        attempts = 1,
        isYou = isYou,
    )

    private fun backend(signedIn: Boolean): IdeBackend {
        val day = UiChallengeDay(
            date = "2026-09-07",
            scheduled = true,
            nextDropAtMs = System.currentTimeMillis() + 6 * 3_600_000L,
            problem = problem,
            solvedCount = 1284,
            solverCount = 2100,
            you = if (signedIn) {
                UiChallengeProgress(signedIn = true, attempts = 2, solved = true, timeClass = "O(n)", spaceClass = "O(n)", runtimeNs = 12_400, solveMs = 420_000, streak = 5)
            } else {
                UiChallengeProgress()
            },
        )
        val profile = if (!signedIn) UiChallengeProfile() else UiChallengeProfile(
            signedIn = true,
            handle = "tyron",
            displayName = "Tyron",
            streak = UiChallengeStreak(current = 5, longest = 11, totalSolved = 37),
            history = listOf(
                UiChallengeHistoryEntry("2026-09-06", "missing-number", "The Missing Number", "easy", true, timeClass = "O(n)"),
                UiChallengeHistoryEntry("2026-09-05", "edit-distance", "Edit Distance", "hard", false),
                UiChallengeHistoryEntry("2026-09-04", "merge-intervals", "Merge Overlapping Intervals", "medium", true, timeClass = "O(n log n)"),
            ),
        )
        return object : StubBackend() {
            override val challenges: ChallengeService = object : ChallengeService {
                override fun challengesAvailable() = true
                override suspend fun day(date: String?) = day
                override suspend fun board(date: String?, limit: Int) = board
                override suspend fun profile() = profile
            }
        }
    }

    @Test
    fun tabSignedInDark() = render("challenge-tab-dark.png", dark = true, signedIn = true)

    @Test
    fun tabSignedOutLight() = render("challenge-tab-signed-out.png", dark = false, signedIn = false)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, dark: Boolean, signedIn: Boolean) {
        val scene = ImageComposeScene(width = 824, height = 1784, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    DailyChallengeScreen(
                        backend = backend(signedIn),
                        epoch = 0,
                        signedIn = signedIn,
                        onSolve = {},
                        onSignIn = {},
                        onOpenBoard = {},
                        onOpenArchive = {},
                    )
                }
            }
        }
        try {
            // Stepped rather than jumped: the tab loads its payloads in a LaunchedEffect, and one large
            // time jump leaves the recomposition pending and renders the loading state.
            scene.render()
            var image = scene.render(0)
            repeat(40) { frame -> image = scene.render(frame * 16_000_000L) }
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
            assertTrue(png.size > 5_000, "the tab should render more than a blank frame")
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun growthChart() {
        // A real measured curve: an O(n) solution doubling with the input, and a quadratic one beside it.
        val linear = listOf(
            UiGrowthPoint(1000, 400, 4040), UiGrowthPoint(2000, 1000, 8040),
            UiGrowthPoint(4000, 2000, 16040), UiGrowthPoint(8000, 4700, 32040),
            UiGrowthPoint(16000, 9600, 64040), UiGrowthPoint(32000, 19700, 128040),
        )
        val quadratic = listOf(
            UiGrowthPoint(1000, 143_284, 72), UiGrowthPoint(2000, 553_289, 72),
            UiGrowthPoint(4000, 2_226_736, 72), UiGrowthPoint(8000, 8_845_447, 72),
            UiGrowthPoint(16000, 35_433_370, 72), UiGrowthPoint(32000, 141_737_073, 72),
        )
        val scene = ImageComposeScene(width = 800, height = 700, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.padding(20.dp)) {
                        // Matching the reference: the two lines run parallel.
                        GrowthChart(
                            points = linear,
                            line = MaterialTheme.colorScheme.primary,
                            grid = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            reference = linear.map { UiGrowthPoint(it.n, (it.ns * 0.8).toLong(), it.bytes) },
                            referenceColor = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(24.dp))
                        // A class behind it: the solid line pulls away from the dashed reference.
                        GrowthChart(
                            points = quadratic,
                            line = MaterialTheme.colorScheme.error,
                            grid = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            reference = linear.map { UiGrowthPoint(it.n, it.ns * 40, it.bytes) },
                            referenceColor = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        try {
            scene.render()
            val png = scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/challenge-growth.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/challenge-growth.png (${png.size} bytes)")
            assertTrue(png.size > 3_000, "the chart should draw more than an empty frame")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
