package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/** `orEmpty()` on nullable String/List/Map/Set receivers is `@InlineOnly`: JetNews `PostScreen` hit it. */
class InlineOnlyOrEmptyTest {
    @Test
    fun orEmptyOnNullAndNonNullReceivers() {
        assertEquals("", runProgram("fun use(): String { val s: String? = null; return s.orEmpty() }", "use/0", emptyList()))
        assertEquals("x", runProgram("fun use(): String { val s: String? = \"x\"; return s.orEmpty() }", "use/0", emptyList()))
        assertEquals(0, runProgram("fun use(): Int { val l: List<Int>? = null; return l.orEmpty().size }", "use/0", emptyList()))
        assertEquals(2, runProgram("fun use(): Int { val l: List<Int>? = listOf(1, 2); return l.orEmpty().size }", "use/0", emptyList()))
        assertEquals(0, runProgram("fun use(): Int { val m: Map<String, Int>? = null; return m.orEmpty().size }", "use/0", emptyList()))
        assertEquals(0, runProgram("fun use(): Int { val s: Set<Int>? = null; return s.orEmpty().size }", "use/0", emptyList()))
    }
}
