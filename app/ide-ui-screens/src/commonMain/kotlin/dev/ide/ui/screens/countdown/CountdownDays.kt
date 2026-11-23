package dev.ide.ui.screens.countdown

import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.day10_body
import dev.ide.ui.generated.resources.day10_title
import dev.ide.ui.generated.resources.day11_body
import dev.ide.ui.generated.resources.day11_title
import dev.ide.ui.generated.resources.day12_body
import dev.ide.ui.generated.resources.day12_title
import dev.ide.ui.generated.resources.day13_body
import dev.ide.ui.generated.resources.day13_title
import dev.ide.ui.generated.resources.day14_body
import dev.ide.ui.generated.resources.day14_title
import dev.ide.ui.generated.resources.day15_body
import dev.ide.ui.generated.resources.day15_title
import dev.ide.ui.generated.resources.day16_body
import dev.ide.ui.generated.resources.day16_title
import dev.ide.ui.generated.resources.day17_body
import dev.ide.ui.generated.resources.day17_title
import dev.ide.ui.generated.resources.day18_body
import dev.ide.ui.generated.resources.day18_title
import dev.ide.ui.generated.resources.day19_body
import dev.ide.ui.generated.resources.day19_title
import dev.ide.ui.generated.resources.day1_body
import dev.ide.ui.generated.resources.day1_title
import dev.ide.ui.generated.resources.day20_body
import dev.ide.ui.generated.resources.day20_title
import dev.ide.ui.generated.resources.day21_body
import dev.ide.ui.generated.resources.day21_title
import dev.ide.ui.generated.resources.day22_body
import dev.ide.ui.generated.resources.day22_title
import dev.ide.ui.generated.resources.day23_body
import dev.ide.ui.generated.resources.day23_title
import dev.ide.ui.generated.resources.day24_body
import dev.ide.ui.generated.resources.day24_title
import dev.ide.ui.generated.resources.day25_body
import dev.ide.ui.generated.resources.day25_title
import dev.ide.ui.generated.resources.day26_body
import dev.ide.ui.generated.resources.day26_title
import dev.ide.ui.generated.resources.day27_body
import dev.ide.ui.generated.resources.day27_title
import dev.ide.ui.generated.resources.day28_body
import dev.ide.ui.generated.resources.day28_title
import dev.ide.ui.generated.resources.day29_body
import dev.ide.ui.generated.resources.day29_title
import dev.ide.ui.generated.resources.day2_body
import dev.ide.ui.generated.resources.day2_title
import dev.ide.ui.generated.resources.day30_body
import dev.ide.ui.generated.resources.day30_title
import dev.ide.ui.generated.resources.day31_body
import dev.ide.ui.generated.resources.day31_title
import dev.ide.ui.generated.resources.day3_body
import dev.ide.ui.generated.resources.day3_title
import dev.ide.ui.generated.resources.day4_body
import dev.ide.ui.generated.resources.day4_title
import dev.ide.ui.generated.resources.day5_body
import dev.ide.ui.generated.resources.day5_title
import dev.ide.ui.generated.resources.day6_body
import dev.ide.ui.generated.resources.day6_title
import dev.ide.ui.generated.resources.day7_body
import dev.ide.ui.generated.resources.day7_title
import dev.ide.ui.generated.resources.day8_body
import dev.ide.ui.generated.resources.day8_title
import dev.ide.ui.generated.resources.day9_body
import dev.ide.ui.generated.resources.day9_title
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import org.jetbrains.compose.resources.StringResource

/** What is behind a day, which decides its picture and the action its sheet offers. */
enum class DayKind {
    /** A short tip, with a code sample when it is about a language. */
    Tip,
    /** A winter problem in the Daily Challenge, scheduled for the day's own date. */
    Challenge,
    /** An editor color scheme to keep. */
    Keepsake,
    /** The New Year card lesson. */
    Lesson,
    /** The Store's winter shelf. */
    Store,
    /** New Year's Eve: fireworks, and the aurora over the sky. */
    Finale,
}

/**
 * One day of the countdown. The words are string resources, so the countdown is translated with the rest of
 * the app; [code] is never translated. [payload] is what the action needs: the keepsake scheme's id, or the
 * lesson track's id.
 */
class CountdownDay(
    val day: Int,
    val kind: DayKind,
    val title: StringResource,
    val body: StringResource,
    val code: String? = null,
    val codeLanguage: String = "kotlin",
    val payload: String? = null,
) {
    /** The Daily Challenge date a [DayKind.Challenge] day points at, in [year]'s December. */
    fun challengeDate(year: Int): String = "$year-12-${day.toString().padStart(2, '0')}"
}

/** The Learn track the lesson day opens. */
const val NEW_YEAR_CARD_TRACK = "new-year-card"

/**
 * The 31 days, December 1 through New Year's Eve. Ten challenges every third day, getting harder toward the
 * end of the year; three keepsake color schemes; the lesson and the Store shelf early, while there is time to
 * enjoy them; tips in between; and New Year's Eve to finish.
 */
object CountdownDays {
    val all: List<CountdownDay> = listOf(
        CountdownDay(1, DayKind.Tip, Res.string.day1_title, Res.string.day1_body),
        CountdownDay(2, DayKind.Challenge, Res.string.day2_title, Res.string.day2_body),
        CountdownDay(
            3, DayKind.Tip, Res.string.day3_title, Res.string.day3_body,
            code = """
                val list = buildString {
                    for (wish in wishes) {
                        append("* ").appendLine(wish)
                    }
                }
            """.trimIndent(),
        ),
        CountdownDay(
            4, DayKind.Keepsake, Res.string.day4_title, Res.string.day4_body,
            payload = BuiltInColorSchemes.FROSTED_GLASS.id,
        ),
        CountdownDay(5, DayKind.Challenge, Res.string.day5_title, Res.string.day5_body),
        CountdownDay(
            6, DayKind.Lesson, Res.string.day6_title, Res.string.day6_body,
            payload = NEW_YEAR_CARD_TRACK,
        ),
        CountdownDay(7, DayKind.Tip, Res.string.day7_title, Res.string.day7_body),
        CountdownDay(8, DayKind.Challenge, Res.string.day8_title, Res.string.day8_body),
        CountdownDay(
            9, DayKind.Tip, Res.string.day9_title, Res.string.day9_body,
            code = """
                data class Mug(val drink: String, val sugar: Int = 0)

                val cocoa = Mug("Cocoa")
                val sweeter = cocoa.copy(sugar = 2)
            """.trimIndent(),
        ),
        CountdownDay(10, DayKind.Store, Res.string.day10_title, Res.string.day10_body),
        CountdownDay(11, DayKind.Challenge, Res.string.day11_title, Res.string.day11_body),
        CountdownDay(
            12, DayKind.Tip, Res.string.day12_title, Res.string.day12_body,
            code = """
                List<String> guests = Arrays.asList("Ana", "Ben", "Chloe");
                String line = String.join(", ", guests);
                // Ana, Ben, Chloe
            """.trimIndent(),
            codeLanguage = "java",
        ),
        CountdownDay(13, DayKind.Tip, Res.string.day13_title, Res.string.day13_body),
        CountdownDay(14, DayKind.Challenge, Res.string.day14_title, Res.string.day14_body),
        CountdownDay(
            15, DayKind.Keepsake, Res.string.day15_title, Res.string.day15_body,
            payload = BuiltInColorSchemes.FIRELIGHT.id,
        ),
        CountdownDay(
            16, DayKind.Tip, Res.string.day16_title, Res.string.day16_body,
            code = """
                val note: String? = card.note
                note?.let { println("Inside: ${'$'}it") } ?: println("No note this time")
            """.trimIndent(),
        ),
        CountdownDay(17, DayKind.Challenge, Res.string.day17_title, Res.string.day17_body),
        CountdownDay(18, DayKind.Tip, Res.string.day18_title, Res.string.day18_body),
        CountdownDay(
            19, DayKind.Tip, Res.string.day19_title, Res.string.day19_body,
            code = """
                val layer = when {
                    temperature < -10 -> "Big coat"
                    temperature < 5 -> "Warm jacket"
                    else -> "Sweater"
                }
            """.trimIndent(),
        ),
        CountdownDay(20, DayKind.Challenge, Res.string.day20_title, Res.string.day20_body),
        CountdownDay(
            21, DayKind.Tip, Res.string.day21_title, Res.string.day21_body,
            code = """
                fun String.sparkly() = "* ${'$'}this *"

                println("Happy coding".sparkly())
                // * Happy coding *
            """.trimIndent(),
        ),
        CountdownDay(
            22, DayKind.Tip, Res.string.day22_title, Res.string.day22_body,
            code = """
                fun greet(name: String, excited: Boolean = false, times: Int = 1) { /* ... */ }

                greet("Ana", times = 3)
            """.trimIndent(),
        ),
        CountdownDay(23, DayKind.Challenge, Res.string.day23_title, Res.string.day23_body),
        CountdownDay(24, DayKind.Tip, Res.string.day24_title, Res.string.day24_body),
        CountdownDay(
            25, DayKind.Keepsake, Res.string.day25_title, Res.string.day25_body,
            payload = BuiltInColorSchemes.NORTHERN_LIGHTS.id,
        ),
        CountdownDay(26, DayKind.Challenge, Res.string.day26_title, Res.string.day26_body),
        CountdownDay(
            27, DayKind.Tip, Res.string.day27_title, Res.string.day27_body,
            code = """
                try (BufferedReader in = new BufferedReader(new FileReader("plans.txt"))) {
                    System.out.println(in.readLine());
                }
                // The reader is closed for you, even if something throws.
            """.trimIndent(),
            codeLanguage = "java",
        ),
        CountdownDay(
            28, DayKind.Tip, Res.string.day28_title, Res.string.day28_body,
            code = """
                repeat(3) { i ->
                    println("Countdown: ${'$'}{3 - i}")
                }
            """.trimIndent(),
        ),
        CountdownDay(29, DayKind.Challenge, Res.string.day29_title, Res.string.day29_body),
        CountdownDay(
            30, DayKind.Tip, Res.string.day30_title, Res.string.day30_body,
            code = """
                val scores = mapOf("Ana" to 12, "Ben" to 9)
                for ((name, score) in scores) {
                    println("${'$'}name scored ${'$'}score")
                }
            """.trimIndent(),
        ),
        CountdownDay(31, DayKind.Finale, Res.string.day31_title, Res.string.day31_body),
    )

    fun of(day: Int): CountdownDay = all[day - 1]
}
