package dev.ide.build.jvm.test.fixtures

import org.junit.Assert.assertEquals
import org.junit.Test

/** A backtick-named test that sorts: `sortedBy` inlines a Comparator class named after the test, spaces and comma
 *  included. On ART that Comparator needs a generated (dexed) peer, whose name D8 refused before minSdk 30. */
class BacktickFixture {
    @Test fun `sources are every file, sorted`() {
        assertEquals(listOf("a.cs", "b.cs", "c.cs"), listOf("b.cs", "c.cs", "a.cs").sortedBy { it })
    }
}
