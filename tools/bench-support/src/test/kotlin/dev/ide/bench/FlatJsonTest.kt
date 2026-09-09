package dev.ide.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the baseline JSON layer round-trips exactly — a benchmark gate is only as good as its store. */
class FlatJsonTest {

    @Test
    fun roundTripsThroughWriteAndRead() {
        val map = mapOf(
            "completion.latency.member-access.ns" to 2_600_000.0,
            "completion.alloc.name-ref.bytes" to 3_145_728.0,
            "completion.quality.overall.mrr" to 0.8421,
            "negative" to -12.5,
            "zero" to 0.0,
            "big" to 1.95e8,
        )
        val back = FlatJson.read(FlatJson.write(map))
        assertEquals(map.keys, back.keys.toSet())
        for ((k, v) in map) assertEquals(v, back.getValue(k), 1e-9, "mismatch on '$k'")
    }

    @Test
    fun readsToleratesWhitespaceAndEmptyObject() {
        assertTrue(FlatJson.read("{}").isEmpty())
        assertTrue(FlatJson.read("  {\n}  ").isEmpty())
        val m = FlatJson.read("{ \"a\" : 1 , \"b\":2.5 }")
        assertEquals(1.0, m["a"])
        assertEquals(2.5, m["b"])
    }

    @Test
    fun integralValuesEmitWithoutExponentOrFraction() {
        val s = FlatJson.write(mapOf("x" to 26_000_000.0))
        assertTrue(s.contains("\"x\": 26000000"), "expected plain decimal, got: $s")
    }
}
