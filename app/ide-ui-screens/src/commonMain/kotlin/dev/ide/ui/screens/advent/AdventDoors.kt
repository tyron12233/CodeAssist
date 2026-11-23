package dev.ide.ui.screens.advent

import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.door10_body
import dev.ide.ui.generated.resources.door10_title
import dev.ide.ui.generated.resources.door11_body
import dev.ide.ui.generated.resources.door11_title
import dev.ide.ui.generated.resources.door12_body
import dev.ide.ui.generated.resources.door12_title
import dev.ide.ui.generated.resources.door13_body
import dev.ide.ui.generated.resources.door13_title
import dev.ide.ui.generated.resources.door14_body
import dev.ide.ui.generated.resources.door14_title
import dev.ide.ui.generated.resources.door15_body
import dev.ide.ui.generated.resources.door15_title
import dev.ide.ui.generated.resources.door16_body
import dev.ide.ui.generated.resources.door16_title
import dev.ide.ui.generated.resources.door17_body
import dev.ide.ui.generated.resources.door17_title
import dev.ide.ui.generated.resources.door18_body
import dev.ide.ui.generated.resources.door18_title
import dev.ide.ui.generated.resources.door19_body
import dev.ide.ui.generated.resources.door19_title
import dev.ide.ui.generated.resources.door1_body
import dev.ide.ui.generated.resources.door1_title
import dev.ide.ui.generated.resources.door20_body
import dev.ide.ui.generated.resources.door20_title
import dev.ide.ui.generated.resources.door21_body
import dev.ide.ui.generated.resources.door21_title
import dev.ide.ui.generated.resources.door22_body
import dev.ide.ui.generated.resources.door22_title
import dev.ide.ui.generated.resources.door23_body
import dev.ide.ui.generated.resources.door23_title
import dev.ide.ui.generated.resources.door24_body
import dev.ide.ui.generated.resources.door24_title
import dev.ide.ui.generated.resources.door25_body
import dev.ide.ui.generated.resources.door25_title
import dev.ide.ui.generated.resources.door2_body
import dev.ide.ui.generated.resources.door2_title
import dev.ide.ui.generated.resources.door3_body
import dev.ide.ui.generated.resources.door3_title
import dev.ide.ui.generated.resources.door4_body
import dev.ide.ui.generated.resources.door4_title
import dev.ide.ui.generated.resources.door5_body
import dev.ide.ui.generated.resources.door5_title
import dev.ide.ui.generated.resources.door6_body
import dev.ide.ui.generated.resources.door6_title
import dev.ide.ui.generated.resources.door7_body
import dev.ide.ui.generated.resources.door7_title
import dev.ide.ui.generated.resources.door8_body
import dev.ide.ui.generated.resources.door8_title
import dev.ide.ui.generated.resources.door9_body
import dev.ide.ui.generated.resources.door9_title
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import org.jetbrains.compose.resources.StringResource

/** What is behind a door, which decides its art and the action its sheet offers. */
enum class DoorKind {
    /** A short tip, with a code sample when it is about a language. */
    Tip,
    /** A holiday problem in the Daily Challenge, scheduled for the door's own date. */
    Challenge,
    /** An editor color scheme to keep. */
    Keepsake,
    /** The Christmas card lesson. */
    Lesson,
    /** The Store's holiday shelf. */
    Store,
    /** Christmas Day: a thank-you, and the star on the tree. */
    Finale,
}

/**
 * One door of the calendar. The words are string resources, so the calendar is translated with the rest of
 * the app; [code] is never translated. [payload] is what the action needs: the keepsake scheme's id, or the
 * lesson track's id.
 */
class AdventDoor(
    val day: Int,
    val kind: DoorKind,
    val title: StringResource,
    val body: StringResource,
    val code: String? = null,
    val codeLanguage: String = "kotlin",
    val payload: String? = null,
) {
    /** The Daily Challenge date a [DoorKind.Challenge] door points at, in [year]'s December. */
    fun challengeDate(year: Int): String = "$year-12-${day.toString().padStart(2, '0')}"
}

/** The Learn track the lesson door opens. */
const val CHRISTMAS_CARD_TRACK = "christmas-card"

/**
 * The 25 doors. Ten challenges spread through the month, getting harder toward Christmas Eve; three keepsake
 * color schemes; the lesson and the Store shelf in the first half, while there is time to enjoy them; tips in
 * between; and Christmas Day to finish.
 */
object AdventDoors {
    val all: List<AdventDoor> = listOf(
        AdventDoor(1, DoorKind.Tip, Res.string.door1_title, Res.string.door1_body),
        AdventDoor(2, DoorKind.Challenge, Res.string.door2_title, Res.string.door2_body),
        AdventDoor(3, DoorKind.Keepsake, Res.string.door3_title, Res.string.door3_body, payload = BuiltInColorSchemes.FROSTED_GLASS.id),
        AdventDoor(
            4, DoorKind.Tip, Res.string.door4_title, Res.string.door4_body,
            code = """
                val list = buildString {
                    for (gift in gifts) {
                        append("* ").appendLine(gift)
                    }
                }
            """.trimIndent(),
        ),
        AdventDoor(5, DoorKind.Challenge, Res.string.door5_title, Res.string.door5_body),
        AdventDoor(6, DoorKind.Lesson, Res.string.door6_title, Res.string.door6_body, payload = CHRISTMAS_CARD_TRACK),
        AdventDoor(7, DoorKind.Tip, Res.string.door7_title, Res.string.door7_body),
        AdventDoor(8, DoorKind.Challenge, Res.string.door8_title, Res.string.door8_body),
        AdventDoor(
            9, DoorKind.Tip, Res.string.door9_title, Res.string.door9_body,
            code = """
                data class Gift(val name: String, val wrapped: Boolean = false)

                val scarf = Gift("Scarf")
                val ready = scarf.copy(wrapped = true)
            """.trimIndent(),
        ),
        AdventDoor(10, DoorKind.Store, Res.string.door10_title, Res.string.door10_body),
        AdventDoor(11, DoorKind.Challenge, Res.string.door11_title, Res.string.door11_body),
        AdventDoor(
            12, DoorKind.Tip, Res.string.door12_title, Res.string.door12_body,
            code = """
                List<String> guests = Arrays.asList("Ana", "Ben", "Chloe");
                String line = String.join(", ", guests);
                // Ana, Ben, Chloe
            """.trimIndent(),
            codeLanguage = "java",
        ),
        AdventDoor(13, DoorKind.Keepsake, Res.string.door13_title, Res.string.door13_body, payload = BuiltInColorSchemes.GINGERBREAD.id),
        AdventDoor(14, DoorKind.Challenge, Res.string.door14_title, Res.string.door14_body),
        AdventDoor(
            15, DoorKind.Tip, Res.string.door15_title, Res.string.door15_body,
            code = """
                val note: String? = card.note
                note?.let { println("Inside: ${'$'}it") } ?: println("No note this year")
            """.trimIndent(),
        ),
        AdventDoor(16, DoorKind.Challenge, Res.string.door16_title, Res.string.door16_body),
        AdventDoor(17, DoorKind.Tip, Res.string.door17_title, Res.string.door17_body),
        AdventDoor(18, DoorKind.Challenge, Res.string.door18_title, Res.string.door18_body),
        AdventDoor(19, DoorKind.Keepsake, Res.string.door19_title, Res.string.door19_body, payload = BuiltInColorSchemes.NORTHERN_LIGHTS.id),
        AdventDoor(20, DoorKind.Challenge, Res.string.door20_title, Res.string.door20_body),
        AdventDoor(
            21, DoorKind.Tip, Res.string.door21_title, Res.string.door21_body,
            code = """
                fun String.festive() = "* ${'$'}this *"

                println("Merry coding".festive())
                // * Merry coding *
            """.trimIndent(),
        ),
        AdventDoor(22, DoorKind.Challenge, Res.string.door22_title, Res.string.door22_body),
        AdventDoor(23, DoorKind.Tip, Res.string.door23_title, Res.string.door23_body),
        AdventDoor(24, DoorKind.Challenge, Res.string.door24_title, Res.string.door24_body),
        AdventDoor(25, DoorKind.Finale, Res.string.door25_title, Res.string.door25_body),
    )

    fun of(day: Int): AdventDoor = all[day - 1]
}
