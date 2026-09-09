package dev.ide.ksp

import java.lang.RuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hard-stop contract for `generateSources`: a KSP run must FAIL when a processor logs an error (Room
 * reporting a schema/DAO/query error), even though KSP2's reflective `execute()` can still return an OK exit
 * code — otherwise the build proceeds to compile against incomplete generated sources and surfaces a confusing
 * downstream error instead of the real cause. Covers the two pieces the production `generate()` composes:
 * [KspSourceGenerator.CollectingLogger] counting errors, and [kspOutcome] failing on them.
 */
class KspOutcomeTest {

    @Test
    fun collectingLoggerCountsErrorsAndExceptionsButNotWarnings() {
        val emitted = mutableListOf<String>()
        val logger = KspSourceGenerator.CollectingLogger { emitted += it }

        logger.info("building", null)
        logger.warn("query verification disabled", null)
        assertEquals(0, logger.errorCount, "info/warn are not errors")

        logger.error("Cannot find setter for field", null)
        logger.exception(RuntimeException("boom"))
        assertEquals(2, logger.errorCount, "error() and exception() both count")
        assertTrue(emitted.any { it.startsWith("ksp error:") }, "error is emitted with the ksp error: prefix")
        assertTrue(emitted.any { it.startsWith("ksp exception:") }, "exception is emitted")
    }

    @Test
    fun exitOkWithNoErrorsSucceedsAndKeepsMessages() {
        val messages = listOf("ksp: building", "ksp warning: schema not exported")
        val r = kspOutcome(exitOk = true, errorCount = 0, messages = messages, moduleName = "app")
        assertTrue(r.success)
        assertEquals(messages, r.messages)
    }

    @Test
    fun exitOkButErrorsLoggedFailsTheRun() {
        val messages = listOf("ksp error: Cannot find setter for field 'x'")
        val r = kspOutcome(exitOk = true, errorCount = 1, messages = messages, moduleName = "app")
        assertFalse(r.success, "a logged error must fail the run even though KSP exited OK")
        assertEquals(messages, r.messages, "the error lines are carried through as the failure reason")
    }

    @Test
    fun nonOkExitFailsAndSynthesizesAReasonWhenNoMessages() {
        val r = kspOutcome(exitOk = false, errorCount = 0, messages = emptyList(), moduleName = "app")
        assertFalse(r.success)
        assertEquals(listOf("ksp: processing failed for app"), r.messages, "a generic reason when nothing was logged")
    }
}
