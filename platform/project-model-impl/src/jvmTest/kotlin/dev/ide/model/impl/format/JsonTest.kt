package dev.ide.model.impl.format

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonTest {

    @Test
    fun roundTripsNestedValues() {
        val value = linkedMapOf(
            "name" to "core",
            "count" to 42L,
            "enabled" to true,
            "ratio" to 3.5,
            "nothing" to null,
            "tags" to listOf("a", "b", 2L, false),
            "nested" to linkedMapOf("x" to "y\"quoted\"\nnewline"),
            "empties" to linkedMapOf("obj" to emptyMap<String, Any?>(), "arr" to emptyList<Any?>()),
        )
        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun integersAreLongRealsAreDouble() {
        val m = Json.parse("""{"i": 42, "r": 3.5, "neg": -7}""") as Map<*, *>
        assertEquals(42L, m["i"])
        assertEquals(3.5, m["r"])
        assertEquals(-7L, m["neg"])
    }

    @Test
    fun parsesEmptyContainers() {
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
    }

    @Test
    fun unescapesStrings() {
        assertEquals("tab\tnl\nq\"bs\\", Json.parse(""" "tab\tnl\nq\"bs\\" """))
    }
}
