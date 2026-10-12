package dev.ide.android.support.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class Aapt2ErrorsTest {

    @Test
    fun readsLocatedErrorsAndDropsTheClosingSummaries() {
        val log = listOf(
            "/p/res/values/values.xml:3: error: resource color/nope (aka com.ex:color/nope) not found.",
            "/p/res/layout/a.xml: error: file failed to compile.",
            "error: failed linking references.",
            "aapt2 exited with code 1",
        )
        assertEquals(
            listOf(Aapt2Error("/p/res/values/values.xml", 3, "resource color/nope (aka com.ex:color/nope) not found.")),
            Aapt2Errors.parse(log),
        )
    }

    @Test
    fun lineZeroMeansUnknown() {
        val e = Aapt2Errors.parse(listOf("/p/v.xml:0: error: xml parser error: not well-formed (invalid token)."))
        assertEquals(listOf(Aapt2Error("/p/v.xml", null, "xml parser error: not well-formed (invalid token).")), e)
    }

    @Test
    fun keepsTheSummariesWhenTheyAreAllThereIs() {
        assertEquals(
            listOf(Aapt2Error(null, null, "failed linking references.")),
            Aapt2Errors.parse(listOf("error: failed linking references.")),
        )
    }

    @Test
    fun ignoresWarningsAndNoise() {
        assertEquals(
            emptyList(),
            Aapt2Errors.parse(listOf("/p/a.xml:2: warn: something.", "[diagnostic] `aapt2 version` works")),
        )
    }
}
