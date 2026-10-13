package dev.ide.core

import dev.ide.build.jvm.test.TestProtocol
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetTemplate
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.UiTestRun
import dev.ide.ui.backend.UiTestCase
import dev.ide.ui.backend.TestResultMemory
import dev.ide.ui.backend.UiTestStatus
import dev.ide.vfs.local.fileFor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Kotlin module's tests run from the Run picker's `test:` task: the tests compile apart from the module, run on
 * the bytecode VM, and the run console's transcript carries the result records the Run screen turns into a test
 * tree ([TestOutput]). kotlin.test assertions run from the project's own kotlin-test jar.
 */
@Timeout(240, unit = TimeUnit.SECONDS)
class TestRunTaskTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    /** JUnit 5 + kotlin.test (with its JUnit 5 annotations), from this test JVM's own classpath. */
    private fun testJars(): List<Path> {
        val wanted = listOf("junit-jupiter-api", "opentest4j", "apiguardian", "junit-platform-commons", "kotlin-test-junit5", "kotlin-test-")
        return System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it) }
            .filter { p -> val n = p.fileName.toString(); n.endsWith(".jar") && wanted.any { n.startsWith(it) } }
            .filter { !it.fileName.toString().startsWith("kotlin-test-junit-") }
    }

    private fun createWorkspace(dir: Path) {
        val platform = PlatformCore()
        try {
            ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            store.workspace.beginModification().apply { addProject("tests", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            val jars = testJars()
            assertTrue(jars.any { it.fileName.toString().startsWith("kotlin-test-junit5") }, "kotlin-test-junit5 on the test classpath: $jars")
            jars.forEachIndexed { i, jar ->
                store.workspace.libraryTable.create("t-$i").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(jar)); commit() }
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", javaLib).apply {
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE))))
                    addSourceSet(SourceSetTemplate("test", DependencyScope.TEST_IMPLEMENTATION, mapOf("src/test/kotlin" to setOf(ContentRole.SOURCE))))
                    jars.indices.forEach { addDependency(LibraryDependency(LibraryRef("t-$it"), DependencyScope.TEST_IMPLEMENTATION)) }
                }
                commit()
            }
            write(dir.resolve("app/src/main/kotlin/com/example/Greeter.kt"), GREETER)
            write(dir.resolve("app/src/test/kotlin/com/example/GreeterTest.kt"), GREETER_TEST)
            store.save()
        } finally {
            platform.dispose()
        }
    }

    private fun write(f: Path, text: String) {
        Files.createDirectories(f.parent)
        Files.writeString(f, text.trimIndent())
    }

    @Test
    fun runsAModulesTestsAndReportsEachOne() {
        withTempDir("test-run-task") { dir ->
            createWorkspace(dir)
            IdeServices.open(dir).use { ide ->
                assertTrue(ide.build.runTasks().any { it.id == "test:app" }, "offered: ${ide.build.runTasks().map { it.id }}")

                ide.build.runTask("test:app")
                await(180_000) { ide.build.runConsole.value?.phase == RunPhase.Finished }
                val rc = assertNotNull(ide.build.runConsole.value)
                val raw = rc.transcript.joinToString("") { it.text }
                val view = TestOutput.split(rc.transcript)
                val tests = assertNotNull(view.tests, "no test records; transcript:\n${raw.replace(TestOutput.MARK, "<M>")}\nlog:\n${ide.build.buildState.value.log.joinToString("\n") { it.message }}")
                val byName = tests.cases.associateBy { it.name }
                assertEquals(UiTestStatus.Passed, byName.getValue("greets by name").status)
                assertEquals(UiTestStatus.Passed, byName.getValue("seesInternals").status)
                val failed = byName.getValue("failsOnPurpose")
                assertEquals(UiTestStatus.Failed, failed.status)
                assertTrue(failed.message.contains("expected <1>, actual <2>", ignoreCase = true), failed.message)
                assertTrue(tests.finished)
                assertEquals(1, rc.exitCode)

                val shown = view.transcript.joinToString("") { it.text }
                assertTrue("printed by a test" in shown, shown)
                assertFalse(TestOutput.MARK in shown, "records are not shown as output")
            }
        }
    }

    @Test
    fun runsOneSelectedTest() {
        withTempDir("test-run-one") { dir ->
            createWorkspace(dir)
            IdeServices.open(dir).use { ide ->
                ide.build.runTask("test:app:com.example.GreeterTest#greets by name")
                await(180_000) { ide.build.runConsole.value?.phase == RunPhase.Finished }
                val rc = assertNotNull(ide.build.runConsole.value)
                val tests = assertNotNull(TestOutput.split(rc.transcript).tests)
                assertEquals(listOf("greets by name"), tests.cases.map { it.name })
                assertEquals(UiTestStatus.Passed, tests.cases.single().status)
                assertEquals(0, rc.exitCode)
            }
        }
    }

    @Test
    fun marksEachTestInTheGutter() {
        withTempDir("test-gutter") { dir ->
            createWorkspace(dir)
            write(dir.resolve("app/src/test/kotlin/com/example/JavaStyleTest.java"), JAVA_TEST)
            IdeServices.open(dir).use { ide ->
                val kt = dir.resolve("app/src/test/kotlin/com/example/GreeterTest.kt")
                val marks = runBlocking { ide.editorDecorations(kt, Files.readString(kt)) }.gutter
                    .filter { it.actionId?.startsWith(TestOutput.GUTTER_ACTION_PREFIX) == true }
                val lines = Files.readAllLines(kt)
                val byAction = marks.associate { it.actionId!!.removePrefix(TestOutput.GUTTER_ACTION_PREFIX) to lines[it.line] }
                assertEquals(4, marks.size, byAction.toString())
                assertTrue("class GreeterTest" in byAction.getValue("test:app:com.example.GreeterTest"))
                assertTrue("`greets by name`" in byAction.getValue("test:app:com.example.GreeterTest#greets by name"))
                assertTrue("seesInternals" in byAction.getValue("test:app:com.example.GreeterTest#seesInternals"))

                val java = dir.resolve("app/src/test/kotlin/com/example/JavaStyleTest.java")
                val javaMarks = runBlocking { ide.editorDecorations(java, Files.readString(java)) }.gutter
                    .mapNotNull { it.actionId?.removePrefix(TestOutput.GUTTER_ACTION_PREFIX) }
                assertEquals(
                    setOf("test:app:com.example.JavaStyleTest", "test:app:com.example.JavaStyleTest#works", "test:app:com.example.JavaStyleTest${'$'}Inner", "test:app:com.example.JavaStyleTest${'$'}Inner#nested"),
                    javaMarks.toSet(),
                )

                // Main sources get no run marks, even with a test-looking annotation.
                val main = dir.resolve("app/src/main/kotlin/com/example/Greeter.kt")
                assertTrue(runBlocking { ide.editorDecorations(main, Files.readString(main)) }.gutter.none { it.actionId?.startsWith(TestOutput.GUTTER_ACTION_PREFIX) == true })
            }
        }
    }

    @Test
    fun splitsRecordsCutAcrossChunks() {
        // The runner's own writer, so the two spellings of the format are held together.
        assertEquals(TestProtocol.MARK, TestOutput.MARK)
        val rec = TestProtocol.record(TestProtocol.FAILED, "a.B.C", "m n", 12, "line1\nl2!")
        val text = "out1\n$rec" + "out2\n"
        // Every cut point, including inside the marker and inside a length-prefixed message.
        for (cut in 1 until text.length) {
            val view = TestOutput.split(listOf(ConsoleChunk(text.substring(0, cut), ConsoleChunkKind.OUTPUT), ConsoleChunk(text.substring(cut), ConsoleChunkKind.OUTPUT)))
            val case = assertNotNull(view.tests, "cut $cut").cases.single()
            assertEquals("a.B.C", case.className)
            assertEquals("m n", case.name)
            assertEquals("line1\nl2!", case.message)
            assertEquals(12, case.durationMs)
            assertEquals("out1\nout2\n", view.transcript.joinToString("") { it.text }, "cut $cut")
        }
    }

    @Test
    fun remembersEachTestsLastOutcomeForTheGutter() {
        fun run(vararg cases: UiTestCase) = UiTestRun(cases.toList(), finished = true)
        val a = "com.example.ATest"
        var memory = TestResultMemory.record(901, "app", run(
            UiTestCase(a, "one", UiTestStatus.Passed), UiTestCase(a, "two", UiTestStatus.Failed),
            UiTestCase(a, "param[1] 1", UiTestStatus.Passed), UiTestCase(a, "param[2] 2", UiTestStatus.Failed),
        ))
        fun status(target: String) = TestResultMemory.statusOf(memory, TestOutput.taskId("app", listOf(target)))
        assertEquals(UiTestStatus.Passed, status("$a#one"))
        assertEquals(UiTestStatus.Failed, status("$a#two"))
        assertEquals(UiTestStatus.Failed, status("$a#param"), "a parameterized test is as bad as its worst invocation")
        assertEquals(UiTestStatus.Failed, status(a), "one failure fails the class")
        assertEquals(null, status("$a#never"))
        assertEquals(null, TestResultMemory.statusOf(memory, TestOutput.taskId("other", listOf("$a#one"))), "keyed by module")

        // Rerunning just the failures replaces their outcome and leaves the rest of the class alone.
        memory = TestResultMemory.record(902, "app", run(
            UiTestCase(a, "two", UiTestStatus.Passed), UiTestCase(a, "param[2] 2", UiTestStatus.Passed),
        ))
        assertEquals(UiTestStatus.Passed, status("$a#two"))
        assertEquals(UiTestStatus.Passed, status(a))
        assertEquals(UiTestStatus.Passed, status("$a#one"))
    }

    private fun await(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(40)
    }

    private companion object {
        const val GREETER = """
            package com.example

            class Greeter { fun greet(name: String) = "Hello, ${'$'}name!" }
            internal fun secret() = 42
        """
        const val JAVA_TEST = """
            package com.example;

            import org.junit.jupiter.api.*;

            class JavaStyleTest {
                @Test void works() {}
                void helper() {}
                @Nested class Inner { @Test void nested() {} }
                @Override public String toString() { return ""; }
            }
        """
        const val GREETER_TEST = """
            package com.example

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class GreeterTest {
                @Test fun `greets by name`() {
                    println("printed by a test")
                    assertEquals("Hello, Ada!", Greeter().greet("Ada"))
                }
                @Test fun seesInternals() { assertEquals(42, secret()) }
                @Test fun failsOnPurpose() { assertEquals(1, 2) }
            }
        """
    }
}
