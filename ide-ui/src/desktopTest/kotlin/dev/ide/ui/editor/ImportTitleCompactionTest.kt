package dev.ide.ui.editor

import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies [importCompactions]: the middle packages COMMON to a set of same-named import candidates collapse to
 * `…` while the segments that DIFFER stay visible (so `material` vs `material3` are both shown, IntelliJ-style),
 * a lone candidate collapses all the way to `Import <first>…<Name>`, and non-imports render verbatim.
 */
class ImportTitleCompactionTest {

    private fun imp(fqn: String) = UiAction(0, "Import $fqn", UiActionKind.QUICK_FIX)

    @Test
    fun loneCandidateCollapsesToFirstAndName() {
        val c = importCompactions(listOf(imp("androidx.compose.something.IconKt")))[0]
        assertEquals("Import androidx…", c?.dimPrefix)
        assertEquals("IconKt", c?.name)
        assertEquals("androidx.compose.something.IconKt", c?.fqn)
    }

    @Test
    fun sameNameKeepsTheDifferingSegment() {
        val m = importCompactions(listOf(imp("androidx.compose.material.Icon"), imp("androidx.compose.material3.Icon")))
        // Common `androidx.compose` collapses; the distinguishing `material` / `material3` stay.
        assertEquals("Import androidx…material.", m[0]?.dimPrefix)
        assertEquals("Import androidx…material3.", m[1]?.dimPrefix)
        assertEquals("Icon", m[0]?.name)
        assertEquals("Icon", m[1]?.name)
    }

    @Test
    fun sameNameDivergingDeeperKeepsThatSegment() {
        val m = importCompactions(
            listOf(
                imp("androidx.compose.material.icons.filled.Icon"),
                imp("androidx.compose.material.icons.outlined.Icon"),
            ),
        )
        assertEquals("Import androidx…filled.", m[0]?.dimPrefix)
        assertEquals("Import androidx…outlined.", m[1]?.dimPrefix)
    }

    @Test
    fun shorterPackageIsDistinguishedFromItsLongerSibling() {
        val m = importCompactions(listOf(imp("androidx.compose.Icon"), imp("androidx.compose.material.Icon")))
        assertEquals("Import androidx…", m[0]?.dimPrefix)          // whole (common) middle collapses
        assertEquals("Import androidx…material.", m[1]?.dimPrefix) // keeps the extra `material`
    }

    @Test
    fun differentNamesAreCompactedIndependently() {
        // A `Box` candidate and an `Icon` candidate don't diff against each other; each collapses on its own.
        val m = importCompactions(listOf(imp("androidx.compose.foundation.layout.Box"), imp("androidx.compose.material.Icon")))
        assertEquals("Box", m[0]?.name)
        assertEquals("Import androidx…", m[0]?.dimPrefix)
        assertEquals("Icon", m[1]?.name)
        assertEquals("Import androidx…", m[1]?.dimPrefix)
    }

    @Test
    fun leavesShallowFqnAlone() {
        assertNull(importCompactions(listOf(imp("java.List")))[0])
    }

    @Test
    fun ignoresNonImportTitles() {
        val m = importCompactions(listOf(UiAction(0, "Create method 'foo'", UiActionKind.QUICK_FIX)))
        assertNull(m[0])
    }
}
