package dev.ide.lang.java

import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.rename.JavaRename
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Highlight-usages-at-caret ([JavaRename.usageHighlightsAt]): the names in the file that ARE the symbol under
 * the caret, each a read, a write or the declaration. A same-named local in another method is not one.
 */
class JavaUsageHighlightTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File

    private val source = """
        package com.foo;
        class Counter {
            int count;
            void bump() {
                count += 1;
                count++;
                System.out.println(count);
                this.count = 5;
                (count) = 6;
            }
            void one() {
                int x = 1;
                x = x + 2;
                System.out.println(x);
            }
            void two() {
                String x = "other";
                System.out.println(x);
            }
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Counter.java").writeText(source)
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    /** The highlights for the caret on the [nth] [needle], as `line:ACCESS` (1-based lines). */
    private fun highlights(needle: String, nth: Int = 0): List<String>? {
        var at = -1
        repeat(nth + 1) { at = source.indexOf(needle, at + 1) }
        check(at >= 0) { "'$needle' not in the source" }
        return JavaRename.usageHighlightsAt(env.parse("com/foo/Counter.java", source), at)
            ?.map { "${source.substring(0, it.range.start).count { c -> c == '\n' } + 1}:${it.access}" }
    }

    @Test
    fun aLocalsUsesInItsOwnMethodOnlyWithTheAssignmentAsAWrite() {
        assertEquals(listOf("12:DECLARATION", "13:WRITE", "13:READ", "14:READ"), highlights("x = x"))
    }

    @Test
    fun aFieldsCompoundAssignmentsIncrementsAndQualifiedAssignmentsAreWrites() {
        assertEquals(
            listOf("3:DECLARATION", "5:WRITE", "6:WRITE", "7:READ", "8:WRITE", "9:WRITE"),
            highlights("count++"),
        )
    }

    @Test
    fun nothingOnAKeyword() {
        assertNull(highlights("void"))
    }
}
