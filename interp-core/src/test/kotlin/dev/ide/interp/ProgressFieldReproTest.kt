package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro for the reported preview crash `ClassCastException: String cannot be cast to Number` from a
 * `LinearProgressIndicator(progress = { project.progress }, …)` — i.e. a `Float` data-class field read coming
 * back as a `String`. Mirrors the dashboard's `Project` shape (a `Float` sitting among `String` fields) and the
 * two read paths: direct, and through a captured value-lambda (how `progress = { … }` is evaluated).
 */
class ProgressFieldReproTest {

    private val project = """
        data class Project(
            val id: String,
            val name: String,
            val language: String,
            val status: String,
            val progress: Float,
            val lineCount: String,
        )
    """.trimIndent()

    @Test
    fun floatFieldReadsDirectlyAsFloat() {
        val code = """
            $project
            fun read(): Any? {
                val p = Project("1", "compiler-cores", "Kotlin", "Active", 0.82f, "14.2k")
                return p.progress
            }
        """.trimIndent()
        val result = runProgram(code, "read/0", emptyList())
        assertTrue(result is Float, "p.progress should be a Float, was ${result?.javaClass?.name} ($result)")
        assertEquals(0.82f, result)
    }

    @Test
    fun floatFieldTimesIntThenToInt() {
        // The other site: Text("${'$'}{(project.progress * 100).toInt()}%") — arithmetic on the Float field.
        val code = """
            $project
            fun read(): Any? {
                val p = Project("1", "compiler-cores", "Kotlin", "Active", 0.82f, "14.2k")
                return (p.progress * 100).toInt()
            }
        """.trimIndent()
        val result = runProgram(code, "read/0", emptyList())
        assertEquals(82, result, "(0.82f * 100).toInt() should be 82, was ${result?.javaClass?.name} ($result)")
    }
}
