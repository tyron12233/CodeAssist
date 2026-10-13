package dev.ide.build.jvm.test

import dev.ide.build.InterpretRunRequest
import dev.ide.build.ProgramIo
import dev.ide.build.jvm.run.VmProgramInterpreter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IDE's test runner end to end: discover the fixtures' tests from their compiled classes, generate the runner,
 * and run it on the real bytecode VM, as a test run on the desktop or on device does.
 */
@Timeout(180, unit = TimeUnit.SECONDS)
class TestRunnerTest {

    private val fixtures = "dev.ide.build.jvm.test.fixtures."

    private class Result(val event: String, val cls: String, val method: String, val ms: Long, val message: String)

    private fun testClassesDir(): Path =
        System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it) }
            .first { it.endsWith(Path.of("classes", "kotlin", "test")) }

    private fun jars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it) }.filter { it.toString().endsWith(".jar") }

    private fun discovered(): List<TestClass> =
        TestDiscovery.discover(listOf(testClassesDir()), jars()).filter { it.fqn.startsWith(fixtures) }

    /** Runs [classes] and returns the protocol records plus the program's own (non-record) output. */
    private fun run(classes: List<TestClass>): Pair<List<Result>, String> {
        val runnerDir = Files.createTempDirectory("test-runner")
        val main = runnerDir.resolve(TestRunnerGenerator.MAIN_CLASS + ".class")
        Files.createDirectories(main.parent)
        Files.write(main, TestRunnerGenerator.generate(classes))
        val out = StringBuilder()
        val io = object : ProgramIo {
            override fun stdout(text: String) { synchronized(out) { out.append(text) } }
            override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
        }
        runBlocking {
            VmProgramInterpreter().run(
                InterpretRunRequest(
                    listOf(runnerDir, testClassesDir()) + jars(),
                    TestRunnerGenerator.MAIN_CLASS.replace('/', '.'),
                    interpretPrefixes = listOf("kotlin/test/"),
                ),
                io,
            )
        }
        lastOutput = out.toString().replace(TestProtocol.MARK, "<MARK>")
        return parse(out.toString())
    }

    /** A minimal reader of the [TestProtocol] records; the host's own lives in ide-ui-api. */
    private fun parse(text: String): Pair<List<Result>, String> {
        val results = ArrayList<Result>()
        val rest = StringBuilder()
        var i = 0
        while (i < text.length) {
            val at = text.indexOf(TestProtocol.MARK, i)
            if (at < 0) { rest.append(text, i, text.length); break }
            rest.append(text, i, at)
            var p = at + TestProtocol.MARK.length
            fun word(): String { val e = text.indexOf(' ', p); val w = text.substring(p, e); p = e + 1; return w }
            fun field(): String {
                val colon = text.indexOf(':', p); val len = text.substring(p, colon).toInt()
                val v = text.substring(colon + 1, colon + 1 + len); p = colon + 1 + len + 1; return v
            }
            val event = word(); val cls = field(); val method = field(); val ms = word().toLong(); val msg = field()
            results += Result(event, cls, method, ms, msg)
            i = p
        }
        return results to rest.toString()
    }

    private fun List<Result>.final(cls: String, method: String): Result =
        lastOrNull { it.cls == fixtures + cls && it.method == method && it.event != TestProtocol.QUEUED && it.event != TestProtocol.STARTED }
            ?: error("no result for $cls.$method; got:\n" + joinToString("\n") { "${it.event} ${it.cls.removePrefix(fixtures)}.${it.method} ${it.message}" } + "\noutput:\n$lastOutput")

    private var lastOutput = ""

    @Test
    fun discoversEveryFixtureShape() {
        val byName = discovered().associateBy { it.fqn.removePrefix(fixtures) }
        val jupiter = byName.getValue("JupiterFixture")
        assertEquals(TestFramework.JUNIT5, jupiter.framework)
        assertEquals(setOf("passes", "fails", "backtick name works", "throws", "disabled"), jupiter.tests.map { it.name }.toSet())
        assertEquals("not today", jupiter.tests.first { it.name == "disabled" }.skipReason)
        assertEquals(listOf("setUpClass"), jupiter.beforeAll.map { it.name })
        val junit4 = byName.getValue("Junit4Fixture")
        assertEquals(TestFramework.JUNIT4, junit4.framework)
        assertEquals("java/lang/IllegalStateException", junit4.tests.first { it.name == "expectedThrows" }.expected)
        assertEquals(listOf("baseBefore", "ownBefore"), byName.getValue("InheritedFixture").beforeEach.map { it.name })
        assertTrue("BaseFixture" !in byName, "an abstract test class is not run on its own")
    }

    @Test
    fun runsEveryFixtureOnTheVm() {
        val (results, output) = run(discovered())
        val r = results

        assertEquals(TestProtocol.PASSED, r.final("JupiterFixture", "passes").event)
        val fails = r.final("JupiterFixture", "fails")
        assertEquals(TestProtocol.FAILED, fails.event)
        assertTrue("math" in fails.message && "expected: <5> but was: <4>" in fails.message, fails.message)
        assertEquals(TestProtocol.PASSED, r.final("JupiterFixture", "backtick name works").event)
        assertEquals("java.lang.IllegalStateException: boom", r.final("JupiterFixture", "throws").message)
        assertEquals(TestProtocol.SKIPPED, r.final("JupiterFixture", "disabled").event)
        assertTrue("must not run" !in output, "a disabled test must not run")

        assertEquals(TestProtocol.PASSED, r.final("Junit4Fixture", "passes").event)
        assertEquals(TestProtocol.PASSED, r.final("Junit4Fixture", "expectedThrows").event)
        val missing = r.final("Junit4Fixture", "expectedMissing")
        assertEquals(TestProtocol.FAILED, missing.event)
        assertTrue("Expected exception: java.lang.IllegalStateException" in missing.message, missing.message)
        assertEquals(TestProtocol.SKIPPED, r.final("Junit4Fixture", "ignored").event)

        assertEquals(TestProtocol.PASSED, r.final("KotlinTestFixture", "kotlinTestPasses").event)
        val kt = r.final("KotlinTestFixture", "kotlinTestFails")
        assertEquals(TestProtocol.FAILED, kt.event)
        assertTrue("expected" in kt.message && "actual" in kt.message, kt.message)

        assertEquals(TestProtocol.PASSED, r.final("InheritedFixture", "inherited").event)
        assertEquals(TestProtocol.PASSED, r.final("InheritedFixture", "own").event)

        // Hooks: beforeAll once, then before/after around every Jupiter test, in that order.
        assertEquals(1, Regex("beforeAll").findAll(output).count(), output)
        assertTrue(output.indexOf("beforeAll") < output.indexOf("\nbefore\nafter"), output)
        assertEquals(4, Regex("\nbefore\nafter").findAll(output).count(), "each run Jupiter test is wrapped: $output")
        assertTrue("base before\nown before" in output, output)
        assertEquals(TestProtocol.FINISHED, r.last().event)
    }

    @Test
    fun runsOnlyTheSelectedMethod() {
        val selector = TestSelector(fixtures + "JupiterFixture", "passes")
        val classes = discovered().filter(selector::matches).map { c -> c.copy(tests = c.tests.filter(selector::matches)) }
        val (results, _) = run(classes)
        val ran = results.filter { it.event == TestProtocol.PASSED || it.event == TestProtocol.FAILED }
        assertEquals(listOf("passes"), ran.map { it.method })
    }
}
