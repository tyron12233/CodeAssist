package dev.ide.core.backend

import dev.ide.ui.backend.BackendText
import dev.ide.ui.backend.UiContentBlock
import dev.ide.ui.backend.UiLesson
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.backend.localized
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** [BackendText]: the table the UI translates backend-authored English through. */
class BackendTextTest {

    @Test
    fun readsAFlatObjectWithEscapes() {
        val t = BackendText.parse("""{"Line one\nline \"two\"": "سطر\tأول", "Café": "مقهى", "a\\b": "ب"}""")
        assertEquals("سطر\tأول", t.translate("Line one\nline \"two\""))
        assertEquals("مقهى", t.translate("Café"))
        assertEquals("ب", t.translate("a\\b"))
        assertNull(t.translate("unknown"))
        assertEquals("unknown", t.tr("unknown"))
    }

    @Test
    fun aMalformedTableIsRejected() {
        assertFailsWith<IllegalArgumentException> { BackendText.parse("""{"a": 1}""") }
        assertFailsWith<IllegalArgumentException> { BackendText.parse("""{"a": "b",}""") }
        assertFailsWith<IllegalArgumentException> { BackendText.parse("""["a"]""") }
    }

    @Test
    fun numbersAreMatchedApartAndPutBackInOrder() {
        val t = BackendText.parse("""{"Heap is ~512 MB; you saved 3072 MB.": "الذاكرة ~512 MB؛ حفظتَ 3072 MB."}""")
        // The device's own numbers, not the ones the table was written with.
        assertEquals("الذاكرة ~1536 MB؛ حفظتَ 4096 MB.", t.translate("Heap is ~1536 MB; you saved 4096 MB."))
        // A different shape of sentence is not a match.
        assertNull(t.translate("Heap is ~1536 MB."))
    }

    @Test
    fun anExactEntryWinsOverANumbersApartOne() {
        val t = BackendText.parse("""{"Go to tab 1": "انتقال إلى علامة التبويب 1", "Go to tab 2": "التبويب الثاني"}""")
        assertEquals("التبويب الثاني", t.translate("Go to tab 2"))
        assertEquals("انتقال إلى علامة التبويب 7", t.translate("Go to tab 7"))
    }

    @Test
    fun aLessonTranslatesItsProseAndLeavesItsCode() {
        val t = BackendText.parse("""{"Variables": "المتغيرات", "Use `val`.": "استخدم `val`.", "Pick one": "اختر واحدًا"}""")
        val lesson = UiLesson(
            id = "l", trackId = "t", title = "Variables", summary = "",
            steps = listOf(
                UiLessonStep.Concept("s1", "Variables", listOf(UiContentBlock.Text("Use `val`."), UiContentBlock.Code("val x = 1"))),
                UiLessonStep.Quiz("s2", "Pick one", "Pick one", listOf("val", "var"), 0),
            ),
        )
        val out = lesson.localized(t)
        assertEquals("المتغيرات", out.title)
        val concept = out.steps[0] as UiLessonStep.Concept
        assertEquals(UiContentBlock.Text("استخدم `val`."), concept.blocks[0])
        assertEquals(UiContentBlock.Code("val x = 1"), concept.blocks[1])
        assertEquals(listOf("val", "var"), (out.steps[1] as UiLessonStep.Quiz).options)
        // No table: the very same object comes back.
        assertSame(lesson, lesson.localized(BackendText.EMPTY))
    }
}
