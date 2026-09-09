package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Smart-ranking and visibility behaviors that put completion on par with a real IDE. */
class SmartCompletionTest {

    @Test
    fun ranksMembersMatchingTheExpectedTypeHigher() {
        val (analyzer, dir) = workspaceWith()
        try {
            // `int x = "".|` — int is expected, so int-returning length() outranks String-returning trim().
            val ordered = completeLabels(analyzer, dir.resolve("app/T.java"), "package app; class T { void m() { int x = \"\".|CARET| } }")
            assertTrue("length" in ordered && "trim" in ordered, "got: $ordered")
            assertTrue(ordered.indexOf("length") < ordered.indexOf("trim"), "expected-type match should rank higher: $ordered")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun demotesObjectMembers() {
        val (analyzer, dir) = workspaceWith()
        try {
            val ordered = completeLabels(analyzer, dir.resolve("app/T.java"), "package app; class T { void m() { String s = \"\"; s.|CARET| } }")
            assertTrue("length" in ordered && "wait" in ordered, "got: $ordered")
            assertTrue(ordered.indexOf("length") < ordered.indexOf("wait"), "java.lang.Object members should rank low: $ordered")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hidesPrivateNestedInterfaceFromAnotherClass() {
        val (analyzer, dir) = workspaceWith(
            "lib/Holder.java" to "package lib; public class Holder { public interface Open {} private interface Hidden {} public static int field = 0; }",
        )
        try {
            val labels = completeLabels(analyzer, dir.resolve("app/T.java"), "package app; import lib.Holder; class T { void m() { Holder.|CARET| } }")
            assertTrue(labels.containsAll(listOf("Open", "field")), "public nested interface + static field expected: $labels")
            assertFalse("Hidden" in labels, "private nested interface must be hidden: $labels")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
