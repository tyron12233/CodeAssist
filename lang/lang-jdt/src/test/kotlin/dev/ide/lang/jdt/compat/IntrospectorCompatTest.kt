package dev.ide.lang.jdt.compat

import java.beans.Introspector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ART shim [IntrospectorCompat.decapitalize] must be byte-for-byte identical to the real
 * [java.beans.Introspector.decapitalize] (the JDK method IntelliJ's `StringUtil`/`PropertyUtilBase`/
 * `ExtensibleQueryFactory` call), since `IntrospectorArtPass` swaps one for the other on device. Verified here
 * on desktop, where the real JDK method exists, across the cases the acronym/first-char rule turns on.
 */
class IntrospectorCompatTest {

    @Test
    fun decapitalizeMatchesTheJdk() {
        val inputs = listOf(
            "", "X", "x", "Name", "getName", "URL", "URLConnection", "aB", "AB", "ABc",
            "iOS", "HTMLEditor", "a", "Ab", "aBc", "  spaces", "123", "Ünïcode",
        )
        for (s in inputs) {
            assertEquals(Introspector.decapitalize(s), IntrospectorCompat.decapitalize(s), "decapitalize(\"$s\")")
        }
    }

    @Test
    fun flushCachesIsANoOpAndDoesNotThrow() {
        IntrospectorCompat.flushCaches()
    }
}
