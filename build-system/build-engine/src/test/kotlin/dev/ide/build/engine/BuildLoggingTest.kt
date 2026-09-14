package dev.ide.build.engine

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.DiagnosticLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The build transcript is a user-facing product: a stack trace is never in it, a tool's chatter is not in
 * the default view, and a problem reads the same in the log as it does in the Problems list.
 */
class BuildLoggingTest {

    @Test fun humanizeDropsStackFramesAndKeepsTheCause() {
        val raw = listOf(
            "Exception in thread \"main\" java.lang.IllegalStateException: cannot open output",
            "\tat com.example.Tool.run(Tool.java:41)",
            "\tat com.example.Tool.main(Tool.java:12)",
            "... 7 more",
        )

        val out = ToolLog.humanize(raw)

        assertEquals(listOf("error: cannot open output"), out)
    }

    @Test fun humanizeCollapsesAFailureEchoedTwice() {
        // D8 prints its failure once as a diagnostic and again as the exception it threw.
        val raw = listOf(
            "error: Type java.lang.Foo is defined multiple times",
            "Exception in thread \"main\" com.android.tools.r8.CompilationFailedException: " +
                "error: Type java.lang.Foo is defined multiple times",
            "\tat com.android.tools.r8.D8.run(D8.java:1)",
        )

        val out = ToolLog.humanize(raw)

        assertEquals(1, out.size, "one problem, reported twice, is still one problem: $out")
    }

    @Test fun levelComesFromTheLinesOwnPrefixAndChatterIsDebug() {
        assertEquals(BuildLogLevel.ERROR, ToolLog.levelOf("e: Main.kt:4:9 Unresolved reference 'x'"))
        assertEquals(BuildLogLevel.ERROR, ToolLog.levelOf("error: cannot link resources"))
        assertEquals(BuildLogLevel.ERROR, ToolLog.levelOf("/a/Main.java:4:9: error: cannot find symbol"))
        assertEquals(BuildLogLevel.WARN, ToolLog.levelOf("w: unused parameter"))
        assertEquals(BuildLogLevel.WARN, ToolLog.levelOf("/a/Main.java:4: warning: deprecated"))
        assertEquals(BuildLogLevel.ERROR, ToolLog.levelOf("1. ERROR in /a/Main.java (at line 4)"))
        // The chatter a tool prints around its problems is real but not news.
        assertEquals(BuildLogLevel.DEBUG, ToolLog.levelOf("D8 (in-process) dexed 41 input(s) -> ext"))
        assertEquals(BuildLogLevel.DEBUG, ToolLog.levelOf("processing /home/me/project/errors/Foo.kt"))
    }

    @Test fun failureSummaryIsASummaryNotTheTranscript() {
        val messages = listOf(
            "/a/Main.kt:4:9: error: Unresolved reference 'x'",
            "/a/Main.kt:9:1: error: Unresolved reference 'y'",
            "/a/Main.kt:12:3: warning: unused",
        )

        assertEquals("Kotlin failed with 2 errors", ToolLog.failureSummary("Kotlin", messages))
        // A single error reads as itself — a count would be less useful than the thing that went wrong.
        assertEquals(
            "/a/Main.kt:4:9: error: Unresolved reference 'x'",
            ToolLog.failureSummary("Kotlin", messages.take(1)),
        )
    }

    @Test fun aDiagnosticRendersAsOneCompilerShapedLine() {
        val diagnostic = BuildDiagnostic(
            severity = BuildSeverity.ERROR,
            message = "Unresolved reference 'viewModle'",
            kind = DiagnosticKind.COMPILER,
            source = "kotlin",
            location = DiagnosticLocation("/home/me/app/src/main/kotlin/MainActivity.kt", 42, 9),
        )

        // The file's name, not its path: the Problems list navigates, the log is read on a phone.
        assertEquals(
            "MainActivity.kt:42:9: error: Unresolved reference 'viewModle'",
            diagnostic.toLogLine(),
        )
    }

    @Test fun aToolsProblemsAndItsTranscriptBothReachTheConsoleExactlyOnce() {
        val entries = ArrayList<BuildLogEntry>()
        val problems = ArrayList<BuildDiagnostic>()
        val ctx = SimpleTaskContext(onLog = { entries.add(it) }, onDiagnostic = { problems.add(it) })

        ctx.toolOutput("aapt2", listOf(
            "aapt2 linking 41 resource(s)",
            "/a/res/values/strings.xml:4:9: error: duplicate value for resource 'app_name'",
        ), DiagnosticKind.RESOURCE)

        assertEquals(1, problems.size, "the located error is one Problem: $problems")
        assertEquals(BuildSeverity.ERROR, problems.single().severity)
        assertEquals(1, entries.count { it.level == BuildLogLevel.ERROR }, "reported once, not twice")
        // The progress line is kept, but out of the default view.
        assertEquals(1, entries.count { it.level == BuildLogLevel.DEBUG })
    }

    @Test fun reportPutsAProblemInBothChannelsAtMatchingLevels() {
        val entries = ArrayList<BuildLogEntry>()
        val problems = ArrayList<BuildDiagnostic>()
        val ctx = SimpleTaskContext(onLog = { entries.add(it) }, onDiagnostic = { problems.add(it) })

        ctx.report(BuildDiagnostic(BuildSeverity.WARNING, "deprecated API", source = "kotlin"))

        assertEquals(1, problems.size)
        assertEquals(BuildLogLevel.WARN, entries.single().level)
        assertTrue("deprecated API" in entries.single().message)
    }

    @Test fun aTranscriptNeverReachesTheDefaultView() {
        val entries = ArrayList<BuildLogEntry>()
        val ctx = SimpleTaskContext(onLog = { entries.add(it) })

        ctx.transcript(listOf("D8 archived 12 input(s)", "\tat com.android.Foo.bar(Foo.java:1)"))

        assertTrue(entries.all { it.level == BuildLogLevel.DEBUG }, "$entries")
        assertFalse(entries.any { "at com.android" in it.message }, "frames are dropped, not demoted: $entries")
    }
}
