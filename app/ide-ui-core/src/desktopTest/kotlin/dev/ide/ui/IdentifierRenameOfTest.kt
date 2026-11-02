package dev.ide.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The check that lets a tab with unsaved edits take a rename's result from disk ([isIdentifierRenameOf]). */
class IdentifierRenameOfTest {

    private val buffer = "val c = Circle(2.0)\nfun area(c: Circle) = c.r // a Circle\n"

    @Test
    fun oneNameRenamedAtSomeOccurrencesIsARename() {
        // The comment's "Circle" is not a reference: a rename leaves it, and that is still one consistent rename.
        assertTrue(isIdentifierRenameOf(buffer, "val c = Round(2.0)\nfun area(c: Round) = c.r // a Circle\n"))
    }

    @Test
    fun anythingElseIsNot() {
        assertFalse(isIdentifierRenameOf(buffer, buffer), "unchanged")
        assertFalse(isIdentifierRenameOf(buffer, buffer.replace("Circle", "Round").replace("area", "size")), "two names")
        assertFalse(isIdentifierRenameOf(buffer, buffer.replace("2.0", "3.0")), "a literal changed")
        assertFalse(isIdentifierRenameOf(buffer, buffer + "// more\n"), "text added")
        assertFalse(isIdentifierRenameOf(buffer, buffer.replace("Circle(2.0)", "Circle (2.0)")), "whitespace changed")
    }
}
