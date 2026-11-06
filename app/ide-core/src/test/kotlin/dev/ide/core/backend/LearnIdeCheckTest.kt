package dev.ide.core.backend

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnIdeCheckTest {

    private class Probe(
        val files: Map<String, String> = emptyMap(),
        val compile: List<String>? = null,
        val run: String? = null,
        val deps: List<String> = emptyList(),
        val keystores: Int = 0,
        val apk: Long = 0L,
        val reflog: String? = null,
        override val stepStartedMillis: Long = 1_700_000_000_000L,
    ) : WorkspaceProbe {
        var compiled = 0
        override fun read(path: String) = files[path]
        override suspend fun compileErrors(): List<String>? { compiled++; return compile }
        override fun runOutputSinceStep() = run
        override fun dependencies() = deps
        override fun keystoreCount() = keystores
        override fun newestApkMillis() = apk
        override fun gitReflog() = reflog
    }

    @Test
    fun everyConditionMustHoldAndEachIsListed() = runBlocking {
        val conditions = listOf(
            IdeCondition.FileContains("Main.kt", "import java.time.LocalDate", "imported"),
            IdeCondition.FileLacks("Main.kt", "greet(", "renamed"),
            IdeCondition.Compiles("compiles"),
        )
        val ok = checkIde(conditions, Probe(files = mapOf("Main.kt" to "import java.time.LocalDate\nfun main() {}")))
        assertTrue(ok.passed, ok.output)
        assertEquals(listOf("✓ imported", "✓ renamed", "✓ compiles"), ok.output.lines())

        val probe = Probe(files = mapOf("Main.kt" to "fun main() { greet() }"), compile = listOf("Unresolved reference: greet"))
        val bad = checkIde(conditions, probe)
        assertFalse(bad.passed)
        assertTrue("imported" in bad.message && "renamed" in bad.message && "compiles" in bad.message, bad.message)
        assertEquals(listOf("Unresolved reference: greet"), bad.diagnostics)
        assertEquals(1, probe.compiled)
    }

    @Test
    fun aMissingFileFailsBothContainsAndLacks() = runBlocking {
        val r = checkIde(listOf(IdeCondition.FileContains("X.kt", "a", "has a"), IdeCondition.FileLacks("X.kt", "b", "lacks b")), Probe())
        assertFalse(r.passed)
        assertEquals(2, r.output.lines().count { it.startsWith("✗") })
    }

    @Test
    fun workspaceFacts() = runBlocking {
        val start = 1_700_000_000_000L
        val conditions = listOf(
            IdeCondition.RunOutput("Hello, ", "ran"),
            IdeCondition.Dependency("com.google.code.gson:gson", "gson"),
            IdeCondition.Keystore("key"),
            IdeCondition.ApkBuilt("apk"),
        )
        val good = Probe(run = "Your name? Ana\nHello, Ana!", deps = listOf("com.google.code.gson:gson:2.11.0"), keystores = 1, apk = start + 5)
        assertTrue(checkIde(conditions, good).passed)
        val stale = Probe(run = null, deps = listOf("com.google.code.gson:gson-extras:1.0".replace("gson-extras", "guava")), keystores = 0, apk = start - 60_000)
        assertEquals(4, checkIde(conditions, stale).output.lines().count { it.startsWith("✗") })
    }

    @Test
    fun commitsAreCountedFromTheReflogSinceTheStepStarted() {
        val start = 1_700_000_000_000L
        val before = "0000 1111 Ana <a@x> ${start / 1000 - 3600} +0000\tcommit (initial): Old"
        val checkout = "1111 2222 Ana <a@x> ${start / 1000 + 10} +0000\tcheckout: moving from main to dev"
        val after = "2222 3333 Ana <a@x> ${start / 1000 + 20} +0000\tcommit: First commit"
        assertEquals(0, commitsSince("$before\n$checkout", start))
        assertEquals(1, commitsSince("$before\n$checkout\n$after", start))
        assertEquals(0, commitsSince(null, start))
        assertEquals(0, commitsSince("garbage without a tab", start))
    }
}
