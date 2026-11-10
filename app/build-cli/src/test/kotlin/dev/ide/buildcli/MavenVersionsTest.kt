package dev.ide.buildcli

import kotlin.test.Test
import kotlin.test.assertEquals

/** The ordering `dexrepo` uses to pick an artifact's newest stable releases. */
class MavenVersionsTest {
    @Test
    fun numericPartsCompareAsNumbers() {
        val sorted = listOf("1.10.0", "1.9.2", "1.9.10", "2.0.0", "1.9").sortedWith(MavenVersions::compare)
        assertEquals(listOf("1.9", "1.9.2", "1.9.10", "1.10.0", "2.0.0"), sorted)
    }
}
