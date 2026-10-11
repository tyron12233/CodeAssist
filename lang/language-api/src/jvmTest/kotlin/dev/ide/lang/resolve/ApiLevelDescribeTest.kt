package dev.ide.lang.resolve

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiLevelDescribeTest {
    @Test
    fun androidLevelsReadAsApiLevels() {
        assertEquals("Added in API 31 · Deprecated in API 33", ApiLevel(31, deprecatedIn = 33).describe())
        assertEquals("Added in API 31\n\nDoc.", ApiLevel(31).prependTo("Doc."))
    }

    @Test
    fun jdkLevelsReadAsJavaReleasesAndTheBaselineSaysNothing() {
        assertEquals("Since Java 11", ApiLevel(11).describe(ApiPlatform.JDK))
        assertEquals("Deprecated in Java 9", ApiLevel(8, deprecatedIn = 9).describe(ApiPlatform.JDK))
        assertEquals("", ApiLevel(8).describe(ApiPlatform.JDK))
        assertEquals("/** Doc. */", ApiLevel(8).prependTo("/** Doc. */", ApiPlatform.JDK))
        assertEquals("/**\n * Since Java 9\n *\n * Doc. */", ApiLevel(9).prependTo("/** Doc. */", ApiPlatform.JDK))
    }
}
