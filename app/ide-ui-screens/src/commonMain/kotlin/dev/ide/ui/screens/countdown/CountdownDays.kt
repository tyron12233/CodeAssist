package dev.ide.ui.screens.countdown

import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.chal10_body
import dev.ide.ui.generated.resources.chal10_title
import dev.ide.ui.generated.resources.chal11_body
import dev.ide.ui.generated.resources.chal11_title
import dev.ide.ui.generated.resources.chal12_body
import dev.ide.ui.generated.resources.chal12_title
import dev.ide.ui.generated.resources.chal13_body
import dev.ide.ui.generated.resources.chal13_title
import dev.ide.ui.generated.resources.chal14_body
import dev.ide.ui.generated.resources.chal14_title
import dev.ide.ui.generated.resources.chal15_body
import dev.ide.ui.generated.resources.chal15_title
import dev.ide.ui.generated.resources.chal16_body
import dev.ide.ui.generated.resources.chal16_title
import dev.ide.ui.generated.resources.chal17_body
import dev.ide.ui.generated.resources.chal17_title
import dev.ide.ui.generated.resources.chal18_body
import dev.ide.ui.generated.resources.chal18_title
import dev.ide.ui.generated.resources.chal19_body
import dev.ide.ui.generated.resources.chal19_title
import dev.ide.ui.generated.resources.chal1_body
import dev.ide.ui.generated.resources.chal1_title
import dev.ide.ui.generated.resources.chal20_body
import dev.ide.ui.generated.resources.chal20_title
import dev.ide.ui.generated.resources.chal21_body
import dev.ide.ui.generated.resources.chal21_title
import dev.ide.ui.generated.resources.chal22_body
import dev.ide.ui.generated.resources.chal22_title
import dev.ide.ui.generated.resources.chal23_body
import dev.ide.ui.generated.resources.chal23_title
import dev.ide.ui.generated.resources.chal24_body
import dev.ide.ui.generated.resources.chal24_title
import dev.ide.ui.generated.resources.chal25_body
import dev.ide.ui.generated.resources.chal25_title
import dev.ide.ui.generated.resources.chal26_body
import dev.ide.ui.generated.resources.chal26_title
import dev.ide.ui.generated.resources.chal27_body
import dev.ide.ui.generated.resources.chal27_title
import dev.ide.ui.generated.resources.chal28_body
import dev.ide.ui.generated.resources.chal28_title
import dev.ide.ui.generated.resources.chal29_body
import dev.ide.ui.generated.resources.chal29_title
import dev.ide.ui.generated.resources.chal2_body
import dev.ide.ui.generated.resources.chal2_title
import dev.ide.ui.generated.resources.chal30_body
import dev.ide.ui.generated.resources.chal30_title
import dev.ide.ui.generated.resources.chal3_body
import dev.ide.ui.generated.resources.chal3_title
import dev.ide.ui.generated.resources.chal4_body
import dev.ide.ui.generated.resources.chal4_title
import dev.ide.ui.generated.resources.chal5_body
import dev.ide.ui.generated.resources.chal5_title
import dev.ide.ui.generated.resources.chal6_body
import dev.ide.ui.generated.resources.chal6_title
import dev.ide.ui.generated.resources.chal7_body
import dev.ide.ui.generated.resources.chal7_title
import dev.ide.ui.generated.resources.chal8_body
import dev.ide.ui.generated.resources.chal8_title
import dev.ide.ui.generated.resources.chal9_body
import dev.ide.ui.generated.resources.chal9_title
import dev.ide.ui.generated.resources.day10_body
import dev.ide.ui.generated.resources.day10_title
import dev.ide.ui.generated.resources.day12_body
import dev.ide.ui.generated.resources.day12_title
import dev.ide.ui.generated.resources.day13_body
import dev.ide.ui.generated.resources.day13_title
import dev.ide.ui.generated.resources.day15_body
import dev.ide.ui.generated.resources.day15_title
import dev.ide.ui.generated.resources.day16_body
import dev.ide.ui.generated.resources.day16_title
import dev.ide.ui.generated.resources.day18_body
import dev.ide.ui.generated.resources.day18_title
import dev.ide.ui.generated.resources.day19_body
import dev.ide.ui.generated.resources.day19_title
import dev.ide.ui.generated.resources.day1_body
import dev.ide.ui.generated.resources.day1_title
import dev.ide.ui.generated.resources.day21_body
import dev.ide.ui.generated.resources.day21_title
import dev.ide.ui.generated.resources.day22_body
import dev.ide.ui.generated.resources.day22_title
import dev.ide.ui.generated.resources.day24_body
import dev.ide.ui.generated.resources.day24_title
import dev.ide.ui.generated.resources.day25_body
import dev.ide.ui.generated.resources.day25_title
import dev.ide.ui.generated.resources.day27_body
import dev.ide.ui.generated.resources.day27_title
import dev.ide.ui.generated.resources.day28_body
import dev.ide.ui.generated.resources.day28_title
import dev.ide.ui.generated.resources.day30_body
import dev.ide.ui.generated.resources.day30_title
import dev.ide.ui.generated.resources.day31_body
import dev.ide.ui.generated.resources.day31_title
import dev.ide.ui.generated.resources.day3_body
import dev.ide.ui.generated.resources.day3_title
import dev.ide.ui.generated.resources.day4_body
import dev.ide.ui.generated.resources.day4_title
import dev.ide.ui.generated.resources.day6_body
import dev.ide.ui.generated.resources.day6_title
import dev.ide.ui.generated.resources.day7_body
import dev.ide.ui.generated.resources.day7_title
import dev.ide.ui.generated.resources.day9_body
import dev.ide.ui.generated.resources.day9_title
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import org.jetbrains.compose.resources.StringResource

/** What a day's extra is, which decides its picture and the action its sheet offers. */
enum class DayKind {
    /** A short tip, with a code sample when it is about a language. */
    Tip,
    /** Only a picture: a day whose one thing is its challenge. */
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

/** A day's challenge: the problem's name and a line of its story. The problem itself is the Daily Challenge's. */
class DayChallenge(val title: StringResource, val teaser: StringResource)

/**
 * A day's extra. The words are string resources, so the countdown is translated with the rest of the app;
 * [code] is never translated. [payload] is what the action needs: the keepsake scheme's id, or the lesson
 * track's id.
 */
class DayBonus(
    val kind: DayKind,
    val title: StringResource,
    val body: StringResource,
    val code: String? = null,
    val codeLanguage: String = "kotlin",
    val payload: String? = null,
)

/** One day of the countdown: a challenge through December 30, an extra on many days, and New Year's Eve. */
class CountdownDay(
    val day: Int,
    val challenge: DayChallenge? = null,
    val bonus: DayBonus? = null,
) {
    /** The Daily Challenge date this day's challenge is scheduled on, in [year]'s December. */
    fun challengeDate(year: Int): String = "$year-12-${day.toString().padStart(2, '0')}"

    /** The picture an opened day shows: its extra's, or the challenge star. */
    val iconKind: DayKind get() = bonus?.kind ?: DayKind.Challenge
}

/** The Learn track the lesson day opens. */
const val NEW_YEAR_CARD_TRACK = "new-year-card"

/**
 * The 31 days, December 1 through New Year's Eve. A Daily Challenge every day through the 30th, easy at the
 * start of the month and hardest at the end; the problems, by day (each is scheduled on that date):
 *
 *    1  winter-favorite-yarn
 *    2  winter-firework-tubes
 *    3  winter-snow-message
 *    4  winter-station-warmup
 *    5  winter-windblown-sign
 *    6  winter-mitten-sort
 *    7  winter-prime-lanterns
 *    8  winter-firewood-pole
 *    9  winter-firework-pairs
 *   10  winter-ticket-streak
 *   11  winter-deeper-snow
 *   12  winter-sculpture-votes
 *   13  winter-backyard-rink
 *   14  winter-snow-gauge
 *   15  winter-fishing-derby
 *   16  winter-puzzle-tiles
 *   17  winter-warning-relay
 *   18  winter-snowplow-loop
 *   19  winter-scarf-stripes
 *   20  winter-rink-cookies
 *   21  winter-ice-floes
 *   22  winter-telescope-nights
 *   23  winter-square-lights
 *   24  winter-sledding-lanes
 *   25  winter-locker-tags
 *   26  winter-market-list
 *   27  winter-snowshoe-trails
 *   28  winter-coat-check
 *   29  winter-snowball-queue
 *   30  winter-new-year-banner
 *
 * The extras sit beside them: tips, three keepsake color schemes, the lesson and the Store shelf early while
 * there is time to enjoy them, and New Year's Eve to finish.
 */
object CountdownDays {
    val all: List<CountdownDay> = listOf(
        CountdownDay(
            1,
            challenge = DayChallenge(Res.string.chal1_title, Res.string.chal1_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day1_title,
                Res.string.day1_body,
            ),
        ),
        CountdownDay(
            2,
            challenge = DayChallenge(Res.string.chal2_title, Res.string.chal2_body),
        ),
        CountdownDay(
            3,
            challenge = DayChallenge(Res.string.chal3_title, Res.string.chal3_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day3_title,
                Res.string.day3_body,
                code = """
                    val list = buildString {
                        for (wish in wishes) {
                            append("* ").appendLine(wish)
                        }
                    }
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            4,
            challenge = DayChallenge(Res.string.chal4_title, Res.string.chal4_body),
            bonus = DayBonus(
                DayKind.Keepsake,
                Res.string.day4_title,
                Res.string.day4_body,
                payload = BuiltInColorSchemes.FROSTED_GLASS.id,
            ),
        ),
        CountdownDay(
            5,
            challenge = DayChallenge(Res.string.chal5_title, Res.string.chal5_body),
        ),
        CountdownDay(
            6,
            challenge = DayChallenge(Res.string.chal6_title, Res.string.chal6_body),
            bonus = DayBonus(
                DayKind.Lesson,
                Res.string.day6_title,
                Res.string.day6_body,
                payload = NEW_YEAR_CARD_TRACK,
            ),
        ),
        CountdownDay(
            7,
            challenge = DayChallenge(Res.string.chal7_title, Res.string.chal7_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day7_title,
                Res.string.day7_body,
            ),
        ),
        CountdownDay(
            8,
            challenge = DayChallenge(Res.string.chal8_title, Res.string.chal8_body),
        ),
        CountdownDay(
            9,
            challenge = DayChallenge(Res.string.chal9_title, Res.string.chal9_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day9_title,
                Res.string.day9_body,
                code = """
                    data class Mug(val drink: String, val sugar: Int = 0)

                    val cocoa = Mug("Cocoa")
                    val sweeter = cocoa.copy(sugar = 2)
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            10,
            challenge = DayChallenge(Res.string.chal10_title, Res.string.chal10_body),
            bonus = DayBonus(
                DayKind.Store,
                Res.string.day10_title,
                Res.string.day10_body,
            ),
        ),
        CountdownDay(
            11,
            challenge = DayChallenge(Res.string.chal11_title, Res.string.chal11_body),
        ),
        CountdownDay(
            12,
            challenge = DayChallenge(Res.string.chal12_title, Res.string.chal12_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day12_title,
                Res.string.day12_body,
                code = """
                    List<String> guests = Arrays.asList("Ana", "Ben", "Chloe");
                    String line = String.join(", ", guests);
                    // Ana, Ben, Chloe
                """.trimIndent(),
                codeLanguage = "java",
            ),
        ),
        CountdownDay(
            13,
            challenge = DayChallenge(Res.string.chal13_title, Res.string.chal13_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day13_title,
                Res.string.day13_body,
            ),
        ),
        CountdownDay(
            14,
            challenge = DayChallenge(Res.string.chal14_title, Res.string.chal14_body),
        ),
        CountdownDay(
            15,
            challenge = DayChallenge(Res.string.chal15_title, Res.string.chal15_body),
            bonus = DayBonus(
                DayKind.Keepsake,
                Res.string.day15_title,
                Res.string.day15_body,
                payload = BuiltInColorSchemes.FIRELIGHT.id,
            ),
        ),
        CountdownDay(
            16,
            challenge = DayChallenge(Res.string.chal16_title, Res.string.chal16_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day16_title,
                Res.string.day16_body,
                code = """
                    val note: String? = card.note
                    note?.let { println("Inside: ${'$'}it") } ?: println("No note this time")
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            17,
            challenge = DayChallenge(Res.string.chal17_title, Res.string.chal17_body),
        ),
        CountdownDay(
            18,
            challenge = DayChallenge(Res.string.chal18_title, Res.string.chal18_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day18_title,
                Res.string.day18_body,
            ),
        ),
        CountdownDay(
            19,
            challenge = DayChallenge(Res.string.chal19_title, Res.string.chal19_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day19_title,
                Res.string.day19_body,
                code = """
                    val layer = when {
                        temperature < -10 -> "Big coat"
                        temperature < 5 -> "Warm jacket"
                        else -> "Sweater"
                    }
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            20,
            challenge = DayChallenge(Res.string.chal20_title, Res.string.chal20_body),
        ),
        CountdownDay(
            21,
            challenge = DayChallenge(Res.string.chal21_title, Res.string.chal21_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day21_title,
                Res.string.day21_body,
                code = """
                    fun String.sparkly() = "* ${'$'}this *"

                    println("Happy coding".sparkly())
                    // * Happy coding *
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            22,
            challenge = DayChallenge(Res.string.chal22_title, Res.string.chal22_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day22_title,
                Res.string.day22_body,
                code = """
                    fun greet(name: String, excited: Boolean = false, times: Int = 1) { /* ... */ }

                    greet("Ana", times = 3)
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            23,
            challenge = DayChallenge(Res.string.chal23_title, Res.string.chal23_body),
        ),
        CountdownDay(
            24,
            challenge = DayChallenge(Res.string.chal24_title, Res.string.chal24_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day24_title,
                Res.string.day24_body,
            ),
        ),
        CountdownDay(
            25,
            challenge = DayChallenge(Res.string.chal25_title, Res.string.chal25_body),
            bonus = DayBonus(
                DayKind.Keepsake,
                Res.string.day25_title,
                Res.string.day25_body,
                payload = BuiltInColorSchemes.NORTHERN_LIGHTS.id,
            ),
        ),
        CountdownDay(
            26,
            challenge = DayChallenge(Res.string.chal26_title, Res.string.chal26_body),
        ),
        CountdownDay(
            27,
            challenge = DayChallenge(Res.string.chal27_title, Res.string.chal27_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day27_title,
                Res.string.day27_body,
                code = """
                    try (BufferedReader in = new BufferedReader(new FileReader("plans.txt"))) {
                        System.out.println(in.readLine());
                    }
                    // The reader is closed for you, even if something throws.
                """.trimIndent(),
                codeLanguage = "java",
            ),
        ),
        CountdownDay(
            28,
            challenge = DayChallenge(Res.string.chal28_title, Res.string.chal28_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day28_title,
                Res.string.day28_body,
                code = """
                    repeat(3) { i ->
                        println("Countdown: ${'$'}{3 - i}")
                    }
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            29,
            challenge = DayChallenge(Res.string.chal29_title, Res.string.chal29_body),
        ),
        CountdownDay(
            30,
            challenge = DayChallenge(Res.string.chal30_title, Res.string.chal30_body),
            bonus = DayBonus(
                DayKind.Tip,
                Res.string.day30_title,
                Res.string.day30_body,
                code = """
                    val scores = mapOf("Ana" to 12, "Ben" to 9)
                    for ((name, score) in scores) {
                        println("${'$'}name scored ${'$'}score")
                    }
                """.trimIndent(),
            ),
        ),
        CountdownDay(
            31,
            bonus = DayBonus(
                DayKind.Finale,
                Res.string.day31_title,
                Res.string.day31_body,
            ),
        ),
    )

    fun of(day: Int): CountdownDay = all[day - 1]
}
