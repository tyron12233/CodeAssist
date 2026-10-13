package dev.ide.build.jvm.test

import dev.ide.build.ProgramIo
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.outputDir
import dev.ide.build.engine.testOutputDir
import dev.ide.build.jvm.JavaBuildSystem
import dev.ide.build.jvm.run.VmProgramInterpreter
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetTemplate
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.Project
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.platform.PluginId
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import dev.ide.vfs.local.fileFor
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A module's tests built and run through [JavaBuildSystem.createTestGraph]: test sources compile into their own
 * output against the main output and the test-scoped classpath (libraries and a `testImplementation` module), and
 * the `test` task runs them on the bytecode VM.
 */
@Timeout(180, unit = TimeUnit.SECONDS)
class TestGraphTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private class CapturingIo : ProgramIo {
        val out = StringBuilder()
        override fun stdout(text: String) { synchronized(out) { out.append(text) } }
        override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private fun main(dir: String) = SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf(dir to setOf(ContentRole.SOURCE)))
    private fun test(dir: String) = SourceSetTemplate("test", DependencyScope.TEST_IMPLEMENTATION, mapOf(dir to setOf(ContentRole.SOURCE)))

    /** JUnit 5's API and what it needs at run time, from this test JVM's own classpath. */
    private fun junitJars(): List<Path> = System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it) }
        .filter { p -> val n = p.fileName.toString(); n.endsWith(".jar") && listOf("junit-jupiter-api", "opentest4j", "apiguardian", "junit-platform-commons").any { n.startsWith(it) } }

    private fun workspace(dir: Path, platform: dev.ide.platform.impl.PlatformCore): Project {
        ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        junitJars().forEachIndexed { i, jar ->
            store.workspace.libraryTable.create("junit-$i").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(jar)); commit() }
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("testkit", javaLib).addSourceSet(main("src/main/java"))
            addModule("calc", javaLib).apply {
                addSourceSet(main("src/main/java"))
                addSourceSet(test("src/test/java"))
                addDependency(ModuleDependency(ModuleId("testkit"), DependencyScope.TEST_IMPLEMENTATION))
                junitJars().indices.forEach { addDependency(LibraryDependency(LibraryRef("junit-$it"), DependencyScope.TEST_IMPLEMENTATION)) }
            }
            commit()
        }
        dir.writeSource("testkit/src/main/java/com/example/testkit/Numbers.java", NUMBERS)
        dir.writeSource("calc/src/main/java/com/example/calc/Calc.java", CALC)
        dir.writeSource("calc/src/test/java/com/example/calc/CalcTest.java", CALC_TEST)
        return store.workspace.projects.single()
    }

    @Test
    fun compilesTheTestsApartAndRunsThem() {
        testEnv("test-graph") { env ->
            val project = workspace(env.dir, env.platform)
            val calc = project.modules.first { it.name == "calc" }
            val io = CapturingIo()
            val graph = JavaBuildSystem().createTestGraph(project, calc, TestSelector.ALL, VmProgramInterpreter(), io)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(env.dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            val out = io.out.toString()
            // One test fails on purpose, so the run (and the build) reports failure.
            assertFalse(outcome.succeeded, "a failing test fails the test task:\n$log")
            assertTrue(record(TestProtocol.PASSED, "addsUsingTheTestkit") in out, out.replace(TestProtocol.MARK, "<M>"))
            assertTrue(record(TestProtocol.FAILED, "subtractsWrongly") in out, out.replace(TestProtocol.MARK, "<M>"))
            assertTrue("expected: <1> but was: <-1>" in out, out.replace(TestProtocol.MARK, "<M>"))

            assertTrue(Files.isRegularFile(testOutputDir(calc).resolve("com/example/calc/CalcTest.class")))
            assertFalse(Files.exists(outputDir(calc).resolve("com/example/calc/CalcTest.class")), "tests stay out of the main output")
        }
    }

    @Test
    fun runsOnlyTheSelectedTest() {
        testEnv("test-graph-one") { env ->
            val project = workspace(env.dir, env.platform)
            val calc = project.modules.first { it.name == "calc" }
            val io = CapturingIo()
            val graph = JavaBuildSystem().createTestGraph(
                project, calc, TestSelector("com.example.calc.CalcTest", "addsUsingTheTestkit"), VmProgramInterpreter(), io,
            )
            val outcome = runBlocking { TaskExecutorImpl(BuildCache(env.dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(), 2) }
            val out = io.out.toString()
            assertTrue(outcome.succeeded, out.replace(TestProtocol.MARK, "<M>"))
            assertEquals(1, Regex(Regex.escape(TestProtocol.MARK) + TestProtocol.PASSED).findAll(out).count())
            assertFalse("subtractsWrongly" in out)
        }
    }

    /** The start of a record for CalcTest's [method] (its elapsed time and message vary). */
    private fun record(event: String, method: String) =
        "${TestProtocol.MARK}$event ${"com.example.calc.CalcTest".length}:com.example.calc.CalcTest ${method.length}:$method "

    private companion object {
        val NUMBERS = """
            package com.example.testkit;
            public class Numbers { public static int two() { return 2; } }
        """
        val CALC = """
            package com.example.calc;
            public class Calc {
                public int add(int a, int b) { return a + b; }
                public int subtract(int a, int b) { return a - b; }
            }
        """
        val CALC_TEST = """
            package com.example.calc;

            import com.example.testkit.Numbers;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class CalcTest {
                @Test void addsUsingTheTestkit() { assertEquals(4, new Calc().add(Numbers.two(), 2)); }
                @Test void subtractsWrongly() { assertEquals(1, new Calc().subtract(1, 2)); }
            }
        """
    }
}
