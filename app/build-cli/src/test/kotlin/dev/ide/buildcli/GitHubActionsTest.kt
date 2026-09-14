package dev.ide.buildcli

import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.UiSeverity
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The workflow commands are a wire format: GitHub parses these lines out of the log, and a single
 * unescaped `:` or a path it cannot anchor silently costs the annotation. The rules are worth pinning.
 */
class GitHubActionsTest {

    @Test
    fun annotationCarriesFileLineAndColumn() {
        val root = Files.createTempDirectory("gha")
        val source = root.resolve("app/src/main/java/Main.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Main {}")

        val lines = capture(root) {
            it.annotate(
                listOf(
                    BuildDiagnosticUi(
                        severity = UiSeverity.Error,
                        message = "Type mismatch",
                        source = "ecj",
                        file = source.toString(),
                        line = 14,
                        column = 9,
                    )
                )
            )
        }

        assertEquals(
            listOf("::error file=app/src/main/java/Main.java,line=14,col=9,title=ecj::Type mismatch"),
            lines,
        )
    }

    @Test
    fun aDiagnosticWithNoPositionStillAnnotatesTheJob() {
        val root = Files.createTempDirectory("gha")
        val lines = capture(root) {
            it.annotate(listOf(BuildDiagnosticUi(UiSeverity.Warning, "no position here")))
        }
        assertEquals(listOf("::warning::no position here"), lines)
    }

    /** A multi-line message would otherwise end the command at the first newline. */
    @Test
    fun dataAndPropertiesAreEscaped() {
        val root = Files.createTempDirectory("gha")
        val lines = capture(root) {
            it.annotate(
                listOf(
                    BuildDiagnosticUi(
                        severity = UiSeverity.Error,
                        message = "expected 100% got\nnothing",
                        source = "a:tool,with punctuation",
                    )
                )
            )
        }
        val line = lines.single()
        assertTrue("title=a%3Atool%2Cwith punctuation" in line, line)
        assertTrue(line.endsWith("expected 100%25 got%0Anothing"), line)
    }

    /** A file outside the checkout cannot be anchored in the diff, so it keeps its absolute path. */
    @Test
    fun aPathOutsideTheProjectStaysAbsolute() {
        val root = Files.createTempDirectory("gha")
        val elsewhere = Files.createTempDirectory("other").resolve("Generated.kt")
        Files.writeString(elsewhere, "// generated")

        val lines = capture(root) {
            it.annotate(listOf(BuildDiagnosticUi(UiSeverity.Error, "boom", file = elsewhere.toString())))
        }
        assertTrue(elsewhere.toString() in lines.single(), lines.single())
    }

    /** Errors annotate before warnings, and positioned diagnostics before unpositioned ones. */
    @Test
    fun theMostUsefulAnnotationsComeFirst() {
        val root = Files.createTempDirectory("gha")
        val source = root.resolve("A.kt").also { Files.writeString(it, "") }
        val lines = capture(root) {
            it.annotate(
                listOf(
                    BuildDiagnosticUi(UiSeverity.Warning, "a warning"),
                    BuildDiagnosticUi(UiSeverity.Error, "unpositioned error"),
                    BuildDiagnosticUi(UiSeverity.Error, "positioned error", file = source.toString(), line = 3),
                )
            )
        }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("::error file=A.kt,line=3::"), lines[0])
        assertEquals("::error::unpositioned error", lines[1])
        assertEquals("::warning::a warning", lines[2])
    }

    @Test
    fun nothingIsEmittedOutsideActions() {
        val root = Files.createTempDirectory("gha")
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out, true))
            GitHubActions(enabled = false, projectRoot = root)
                .annotate(listOf(BuildDiagnosticUi(UiSeverity.Error, "quiet")))
        } finally {
            System.setOut(original)
        }
        assertEquals("", out.toString().trim())
    }

    /** Run [block] with stdout captured, and return the workflow-command lines it produced. */
    private fun capture(root: Path, block: (GitHubActions) -> Unit): List<String> {
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out, true))
            block(GitHubActions(enabled = true, projectRoot = root))
        } finally {
            System.setOut(original)
        }
        return out.toString().lines().filter { it.startsWith("::") }
    }
}
