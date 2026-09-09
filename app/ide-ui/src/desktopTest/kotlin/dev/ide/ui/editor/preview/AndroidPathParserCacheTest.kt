package dev.ide.ui.editor.preview

import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [AndroidPathParser.cached] backs the drawable canvas, which re-parsed every path of every visible icon on
 * every frame — an icon grid paid that per tile per scroll frame. Real Material Symbols path data, since the
 * cost is proportional to the command count and a toy `M0,0L1,1` would measure nothing.
 */
class AndroidPathParserCacheTest {

    private val search = "M784-120 532-372q-30 24-69 38t-83 14q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l252 252-56 56ZM380-400q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z"
    private val delete = "M280-120q-33 0-56.5-23.5T200-200v-520h-40v-80h200v-40h240v40h200v80h-40v520q0 33-23.5 56.5T680-120H280Zm400-600H280v520h400v-520ZM360-280h80v-360h-80v360Zm160 0h80v-360h-80v360ZM280-720v520-520Z"
    private val mail = "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm320-280L160-640v400h640v-400L480-440Zm0-80 320-200H160l320 200ZM160-640v-80 480-400Z"
    private val samples = listOf(search, delete, mail)

    @Test
    fun cachedPathMatchesAFreshParse() {
        for (data in samples) {
            for (evenOdd in listOf(false, true)) {
                val fresh = AndroidPathParser.parse(data, evenOdd)
                val cached = AndroidPathParser.cached(data, evenOdd)
                assertEquals(fresh.getBounds(), cached.getBounds(), "bounds differ for $data (evenOdd=$evenOdd)")
                assertEquals(fresh.fillType, cached.fillType, "fill type differs (evenOdd=$evenOdd)")
            }
        }
    }

    /** The fill rule is part of the identity — an even-odd path is a DIFFERENT path, not a cache hit. */
    @Test
    fun theFillRuleIsPartOfTheKey() {
        assertSame(AndroidPathParser.cached(search), AndroidPathParser.cached(search))
        assertNotSame(AndroidPathParser.cached(search, fillEvenOdd = false), AndroidPathParser.cached(search, fillEvenOdd = true))
    }

    /** [AndroidPathParser.parse] must keep handing out OWNED paths — the layout/RealView render backends wrap
     *  the result and transform it, so they must never receive the shared instance. */
    @Test
    fun parseStillReturnsAFreshPathEveryTime() {
        assertNotSame(AndroidPathParser.parse(search), AndroidPathParser.parse(search))
        assertNotSame(AndroidPathParser.cached(search), AndroidPathParser.parse(search))
    }

    @Test
    fun aRepeatLookupIsFarCheaperThanParsing() {
        repeat(200) { for (d in samples) AndroidPathParser.cached(d) }   // warm both JIT and cache
        val reps = 200
        val parseNs = measureNanoTime { repeat(reps) { for (d in samples) AndroidPathParser.parse(d) } }
        val cachedNs = measureNanoTime { repeat(reps) { for (d in samples) AndroidPathParser.cached(d) } }
        // Measured ~450x on a desktop JVM; assert only an order of magnitude so a loaded CI box can't flake it.
        assertTrue(
            cachedNs * 10 < parseNs,
            "a cache hit should be far cheaper than parsing: parse=${parseNs / 1000}us cached=${cachedNs / 1000}us",
        )
    }

    /** The cache is bounded, so a project with pathological drawable churn evicts instead of growing. */
    @Test
    fun theCacheIsBounded() {
        repeat(2_000) { i -> AndroidPathParser.cached("M0,0L$i,${i + 1}Z") }
        // Nothing observable should break, and the most recent entries stay hits.
        assertSame(AndroidPathParser.cached("M0,0L1999,2000Z"), AndroidPathParser.cached("M0,0L1999,2000Z"))
    }
}
