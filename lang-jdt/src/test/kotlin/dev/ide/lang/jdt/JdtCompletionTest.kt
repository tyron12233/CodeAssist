package dev.ide.lang.jdt

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the three issues that motivated the JDT backend: tolerance, smartness, freshness. */
class JdtCompletionTest {

    private val helper =
        "package lib; public class Helper { public static String stat() { return null; } public String inst() { return null; } public static int SFIELD = 0; public int field = 0; }"

    @Test
    fun completesEvenWhenSurroundingCodeIsBroken() {
        // An unterminated call earlier in the method — JavaParser would give up; JDT recovers.
        val (analyzer, dir) = workspaceWith()
        try {
            val code = "package app; class T { void m() { System.out.println( ; String s = \"\"; s.|CARET| } }"
            val labels = completeLabels(analyzer, dir.resolve("app/T.java"), code)
            assertTrue(labels.containsAll(listOf("length", "substring", "isEmpty")), "broken-code completion failed: $labels")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun staticContextShowsOnlyStaticMembers() {
        val (analyzer, dir) = workspaceWith("lib/Helper.java" to helper)
        try {
            val labels = completeLabels(
                analyzer, dir.resolve("app/T.java"),
                "package app; import lib.Helper; class T { void m() { Helper.|CARET| } }",
            )
            assertTrue(labels.containsAll(listOf("stat", "SFIELD")), "static members expected: $labels")
            assertFalse("inst" in labels, "instance method must be hidden in a static context: $labels")
            assertFalse("field" in labels, "instance field must be hidden in a static context: $labels")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun completesStaticMembersOnFullyQualifiedType() {
        // The reported regression: `java.util.List.` must resolve the FQ type and show its static members.
        val (analyzer, dir) = workspaceWith()
        try {
            val labels = completeLabels(
                analyzer, dir.resolve("app/T.java"),
                "package app; class T { void m() { java.util.List.|CARET| } }",
            )
            assertTrue("of" in labels, "expected static List.of: $labels")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun instanceContextShowsInstanceMembers() {
        val (analyzer, dir) = workspaceWith("lib/Helper.java" to helper)
        try {
            val labels = completeLabels(
                analyzer, dir.resolve("app/T.java"),
                "package app; import lib.Helper; class T { void m() { Helper h = new Helper(); h.|CARET| } }",
            )
            assertTrue(labels.containsAll(listOf("inst", "field")), "instance members expected: $labels")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hidesPrivateMembersFromAnotherClass() {
        val (analyzer, dir) = workspaceWith(
            "lib/Secret.java" to "package lib; public class Secret { public String shown() { return null; } private String hidden() { return null; } }",
        )
        try {
            val labels = completeLabels(
                analyzer, dir.resolve("app/T.java"),
                "package app; import lib.Secret; class T { void m() { Secret s = new Secret(); s.|CARET| } }",
            )
            assertTrue("shown" in labels, "public member expected: $labels")
            assertFalse("hidden" in labels, "private member must be hidden from another class: $labels")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun picksUpEditsToADependencySourceFileOnReparse() {
        val (analyzer, dir) = workspaceWith("lib/Helper.java" to helper)
        try {
            val tFile = dir.resolve("app/T.java")
            val code = "package app; import lib.Helper; class T { void m() { Helper.|CARET| } }"
            assertFalse("freshMethod" in completeLabels(analyzer, tFile, code), "method should not exist yet")

            // Edit the dependency on disk (what the editor flushes), then re-complete.
            Files.writeString(
                dir.resolve("lib/Helper.java"),
                helper.dropLast(1) + " public static int freshMethod() { return 1; } }",
            )
            assertTrue("freshMethod" in completeLabels(analyzer, tFile, code), "new static method must appear after the dependency edit")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
