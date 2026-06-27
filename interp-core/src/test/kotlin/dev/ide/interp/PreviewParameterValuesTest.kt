package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/** A library-style `PreviewParameterProvider`: a public class exposing a `values` sequence (its JVM getter is
 *  `getValues()`), resolved + instantiated reflectively the way the on-device renderer loads a library provider. */
class SampleStringProvider {
    val values: Sequence<String> get() = sequenceOf("alpha", "beta", "gamma")
}

/** [Interpreter.previewParameterValues] feeds `@PreviewParameter` previews: it instantiates the provider and
 *  reads its sample values, honoring `limit`. The renderer then composes one entry per value. */
class PreviewParameterValuesTest {

    private val interpreter = Interpreter(emptyMap())

    @Test
    fun readsLibraryProviderValues() {
        val values = interpreter.previewParameterValues(null, "dev.ide.interp.SampleStringProvider", Int.MAX_VALUE)
        assertEquals(listOf("alpha", "beta", "gamma"), values)
    }

    @Test
    fun respectsLimit() {
        val values = interpreter.previewParameterValues(null, "dev.ide.interp.SampleStringProvider", 2)
        assertEquals(listOf("alpha", "beta"), values)
    }

    @Test
    fun unknownProviderYieldsEmpty() {
        assertEquals(emptyList(), interpreter.previewParameterValues(null, "does.not.Exist", 5))
    }

    @Test
    fun noProviderYieldsEmpty() {
        assertEquals(emptyList(), interpreter.previewParameterValues(null, null, 5))
    }
}
