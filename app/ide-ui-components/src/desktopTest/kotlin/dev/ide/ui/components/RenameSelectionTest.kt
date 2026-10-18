package dev.ide.ui.components

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the rename dialog pre-selects so typing replaces it. */
class RenameSelectionTest {

    @Test
    fun fileSelectsItsStem() {
        assertEquals(TextRange(0, 7), renameSelection("Example.java", isFile = true))
        assertEquals(TextRange(0, 12), renameSelection("build.gradle.kts", isFile = true))
    }

    @Test
    fun fileWithoutAStemSelectsEverything() {
        assertEquals(TextRange(0, 10), renameSelection(".gitignore", isFile = true))
        assertEquals(TextRange(0, 8), renameSelection("Makefile", isFile = true))
    }

    @Test
    fun folderSelectsTheWholeNameEvenWithDots() {
        assertEquals(TextRange(0, 4), renameSelection("util", isFile = false))
        assertEquals(TextRange(0, 10), renameSelection("values-v21", isFile = false))
        assertEquals(TextRange(0, 7), renameSelection("foo.bar", isFile = false))
    }
}
