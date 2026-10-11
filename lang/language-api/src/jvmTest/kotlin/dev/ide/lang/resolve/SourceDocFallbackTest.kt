package dev.ide.lang.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SourceDocFallbackTest {

    private fun provider(fqn: String, names: List<String>, raw: String) = object : SourceDocProvider {
        override fun method(declaringFqn: String, methodName: String, arity: Int) =
            if (declaringFqn == fqn) SourceDocProvider.MethodDoc(names, raw.removeSurrounding("/** ", " */")) else null
        override fun methodRaw(declaringFqn: String, methodName: String, arity: Int) = if (declaringFqn == fqn) raw else null
    }

    @Test
    fun theFirstProviderWinsAndTheNextFillsItsGaps() {
        val sources = provider("a.Attached", listOf("fromSources"), "/** sources */")
        val bundled = provider("a.Bundled", listOf("fromPack"), "/** pack */")
        val both = sources.orElse(bundled)
        assertEquals(listOf("fromSources"), both.method("a.Attached", "m", 1)?.names)
        assertEquals(listOf("fromPack"), both.method("a.Bundled", "m", 1)?.names)
        assertEquals("/** pack */", both.methodRaw("a.Bundled", "m", 1))
        assertNull(both.method("a.Neither", "m", 1))
    }

    @Test
    fun noneIsDroppedFromEitherSide() {
        val p = provider("a.A", emptyList(), "/** a */")
        assertSame(p, p.orElse(SourceDocProvider.NONE))
        assertSame(p, SourceDocProvider.NONE.orElse(p))
    }
}
