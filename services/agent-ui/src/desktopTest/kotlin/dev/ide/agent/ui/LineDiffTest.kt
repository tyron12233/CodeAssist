package dev.ide.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDiffTest {
    @Test
    fun aOneLineEditInALongFileIsOneSmallHunk() {
        val before = (1..100).joinToString("\n") { "line $it" }
        val after = before.replace("line 50", "line fifty")
        val result = LineDiff.diff(before, after)
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        val hunk = result.hunks.single()
        // Three lines of context either side of the change.
        assertEquals(8, hunk.lines.size)
        val removed = hunk.lines.single { it.kind == LineDiff.Kind.REMOVED }
        assertEquals("line 50", removed.text)
        assertEquals(50, removed.oldLine)
        assertEquals(50, hunk.lines.single { it.kind == LineDiff.Kind.ADDED }.newLine)
    }

    @Test
    fun distantChangesAreSeparateHunks() {
        val before = (1..60).joinToString("\n") { "l$it" }
        val after = before.replace("l5\n", "l5x\n").replace("l55\n", "l55x\n")
        assertEquals(2, LineDiff.diff(before, after).hunks.size)
    }

    @Test
    fun aNewFileIsAllAdditionsAndADeletedOneAllRemovals() {
        val created = LineDiff.diff(null, "a\nb")
        assertEquals(2, created.added)
        assertEquals(0, created.removed)
        val deleted = LineDiff.diff("a\nb\nc", null)
        assertEquals(3, deleted.removed)
    }

    @Test
    fun interleavedInsertionsAndDeletionsAreMinimal() {
        val result = LineDiff.diff("a\nb\nc\nd\ne", "a\nc\nd\nx\ne")
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        val kinds = result.hunks.single().lines.map { it.kind to it.text }
        assertTrue(LineDiff.Kind.REMOVED to "b" in kinds)
        assertTrue(LineDiff.Kind.ADDED to "x" in kinds)
    }

    @Test
    fun identicalTextHasNoHunks() {
        assertTrue(LineDiff.diff("same\ntext", "same\ntext").hunks.isEmpty())
    }
}
