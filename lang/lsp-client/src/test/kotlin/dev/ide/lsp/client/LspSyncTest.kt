package dev.ide.lsp.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LspSyncTest {

    /** What a server does with an incremental change: replace the range (as positions) with the text. */
    private fun applyAsServer(old: String, new: String): String {
        val change = LspSession.incrementalChange(old, new)
        val lines = LineIndex(old)
        // Round-trip through positions, as the wire does.
        val start = lines.toOffset(lines.position(change.start))!!
        val end = lines.toOffset(lines.position(change.oldEnd))!!
        return old.substring(0, start) + change.text + old.substring(end)
    }

    @Test
    fun anIncrementalChangeReproducesTheNewText() {
        val cases = listOf(
            "abc" to "abXc", "abc" to "", "" to "x", "hello\nworld" to "hello\nbrave world",
            "a\r\nb" to "a\r\r\nb", "a\r\nb" to "a\nb", "x😀y" to "x😁y", "same" to "same",
            "line1\nline2\nline3" to "line1\nline3",
        )
        for ((old, new) in cases) assertEquals(new, applyAsServer(old, new), "'$old' -> '$new'")

        val random = Random(7)
        val alphabet = "ab\n\r😀 "
        repeat(2_000) {
            val old = String(CharArray(random.nextInt(0, 30)) { alphabet[random.nextInt(alphabet.length)] })
            val sb = StringBuilder(old)
            repeat(random.nextInt(1, 4)) {
                val at = random.nextInt(0, sb.length + 1)
                if (random.nextBoolean() && at < sb.length) sb.deleteCharAt(at) else sb.insert(at, alphabet[random.nextInt(alphabet.length)])
            }
            val new = sb.toString()
            assertEquals(new, applyAsServer(old, new), "'$old' -> '$new'")
        }
    }

    @Test
    fun aChangeIsOnlyTheSpanThatChanged() {
        val change = LspSession.incrementalChange("let width = 4", "let height = 4")
        assertEquals(4, change.start)
        assertEquals(9, change.oldEnd)
        assertEquals("height", change.text)
    }

    @Test
    fun tokenDeltasApplyAgainstThePreviousArray() {
        val previous = intArrayOf(0, 0, 3, 1, 0, 0, 4, 5, 0, 0)
        val edits = Json.parseToJsonElement("""[{"start":5,"deleteCount":5,"data":[1,0,3,1,0,0,4,2,0,1]}]""") as JsonArray
        assertContentEquals(intArrayOf(0, 0, 3, 1, 0, 1, 0, 3, 1, 0, 0, 4, 2, 0, 1), LspConvert.applyTokenEdits(previous, edits))
        val outOfRange = Json.parseToJsonElement("""[{"start":8,"deleteCount":5}]""") as JsonArray
        assertNull(LspConvert.applyTokenEdits(previous, outOfRange))
    }
}
