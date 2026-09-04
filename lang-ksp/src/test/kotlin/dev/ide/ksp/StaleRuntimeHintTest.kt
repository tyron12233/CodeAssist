package dev.ide.ksp

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fallback for a version skew the catalog doesn't yet know about ([staleRuntimeHint]).
 *
 * [KspProcessorCatalog.blessed]'s member floor fails the KNOWN ones (Hilt's `AggregatedRoot`) before a
 * processor runs. When an unlisted one gets through, the build console is left with the framework's own
 * words — "Collection contains no element matching the predicate" — which name neither the library, the
 * version, nor the annotation. This turns that into a direction to look in.
 */
class StaleRuntimeHintTest {

    /** Verbatim from a Hilt 2.51.1 project, which is how the failure actually reaches the console: Hilt
     *  CATCHES it and logs it, stack trace and all, so the hint has to match reported text, not a Throwable. */
    private val hiltReport = """
        ksp error: [Hilt] Collection contains no element matching the predicate.: java.util.NoSuchElementException: Collection contains no element matching the predicate.
            at dagger.spi.internal.shaded.androidx.room3.compiler.processing.ksp.KspAnnotationValue${'$'}Companion.findMethod(KspAnnotationValue.kt:193)
            at dagger.spi.internal.shaded.androidx.room3.compiler.processing.ksp.KspAnnotationValue${'$'}Companion.create(KspAnnotationValue.kt:53)
            at dagger.hilt.processor.internal.root.AggregatedRootMetadata.create(AggregatedRootMetadata.java:93)
    """.trimIndent()

    @Test
    fun theAnnotationElementLookupFailureIsExplained() {
        val hint = assertNotNull(staleRuntimeHint(hiltReport), "the reported failure must be recognised")
        assertTrue("older than the bundled processor" in hint, "names the cause: $hint")
        assertTrue("annotation element" in hint, "names what was missing: $hint")
        assertTrue("Update that library's runtime" in hint, "says what to do: $hint")
    }

    /** Unshaded XProcessing (Room's own copy) reports through the same class, so the match can't be
     *  anchored on Hilt's shaded package. */
    @Test
    fun theUnshadedFrameworkIsRecognisedToo() {
        assertNotNull(
            staleRuntimeHint(
                "at androidx.room.compiler.processing.ksp.KspAnnotationValue\$Companion.findMethod(...)\n" +
                    "java.util.NoSuchElementException: Collection contains no element matching the predicate."
            ),
        )
    }

    @Test
    fun unrelatedFailuresGetNoHint() {
        assertNull(staleRuntimeHint(""))
        assertNull(staleRuntimeHint("ksp error: Cannot find setter for field."), "an ordinary processor error")
        assertNull(
            staleRuntimeHint("java.util.NoSuchElementException: Collection contains no element matching the predicate."),
            "the message alone is not enough — plenty of code throws it; the framework frame is the signal",
        )
    }
}
