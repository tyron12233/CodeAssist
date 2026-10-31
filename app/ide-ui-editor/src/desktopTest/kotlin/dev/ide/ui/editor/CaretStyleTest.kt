package dev.ide.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class CaretStyleTest {

    @Test
    fun idsMatchWhatTheSettingsStorePersists() {
        // The store's allow-list (IdeSettings.CARET_*) is in ide-core, which this module cannot see, so the
        // literal ids are pinned here instead.
        assertEquals(listOf("line", "block", "underline", "candle"), CaretStyle.entries.map { it.id })
    }

    @Test
    fun anUnknownOrMissingIdIsTheLine() {
        assertEquals(CaretStyle.Line, CaretStyle.fromId(null))
        assertEquals(CaretStyle.Line, CaretStyle.fromId("broomstick"))
        assertEquals(CaretStyle.Candle, CaretStyle.fromId("candle"))
    }
}
