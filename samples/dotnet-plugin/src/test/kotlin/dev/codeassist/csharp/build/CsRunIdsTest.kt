package dev.codeassist.csharp.build

import dev.codeassist.csharp.build.tasks.CsRunIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsRunIdsTest {

    @Test
    fun `the id carries the module right after the kind`() {
        assertEquals("csRun:App", CsRunIds.taskId("App"))
        assertEquals("csRun:App:N.Cli", CsRunIds.taskId("App", "N.Cli"))
    }

    @Test
    fun `an id parses back into its module and class`() {
        val plain = CsRunIds.parse("csRun:App")!!
        assertEquals("App", plain.module)
        assertNull(plain.mainClass)
        val cls = CsRunIds.parse(CsRunIds.taskId("App", "N.Cli"))!!
        assertEquals("App", cls.module)
        assertEquals("N.Cli", cls.mainClass)
    }

    @Test
    fun `other ids are not ours`() {
        assertNull(CsRunIds.parse("run:App"))
        assertNull(CsRunIds.parse("pubGet:App"))
        assertNull(CsRunIds.parse("csRun:"))
        assertNull(CsRunIds.parse("csRun::X"))
    }

    @Test
    fun `the simple name is the last segment`() {
        assertEquals("Program", CsRunIds.simpleName("N.Outer.Program"))
        assertEquals("Program", CsRunIds.simpleName("Program"))
    }

    @Test
    fun `the restore row has an id of its own`() {
        assertEquals("csRestore:App", CsRunIds.restoreId("App"))
        assertEquals("App", CsRunIds.restoreModule("csRestore:App"))
        assertNull(CsRunIds.restoreModule("csRun:App"))
        assertNull(CsRunIds.restoreModule("csRestore:"))
        // The two kinds of id never read as each other.
        assertNull(CsRunIds.parse("csRestore:App"))
    }
}
