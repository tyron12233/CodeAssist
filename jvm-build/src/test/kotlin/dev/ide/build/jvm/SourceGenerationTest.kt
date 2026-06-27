package dev.ide.build.jvm

import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.SourceGenRequest
import dev.ide.build.SourceGenResult
import dev.ide.build.SourceGenerator
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.jarPath
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generic source-generation seam ([SourceGenerator] -> `generateSources` -> compile): a generator emits
 * a `.java` into the module's `ContentRole.GENERATED` root, and the existing `compileJava` compiles it
 * alongside the hand-written sources (which reference it) with no compile-task change — proving the flow KSP
 * will ride. Also checks the graph edge (`compileJava` depends on `generateSources`) and incrementality (an
 * unchanged rebuild is up-to-date; the generator does not re-run).
 */
class SourceGenerationTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    /** Writes `com.gen.Greeting` into the generated root; counts its invocations for the up-to-date check. */
    private class StubGenerator(val runs: AtomicInteger) : SourceGenerator {
        override val id = "stub"
        override fun appliesTo(request: SourceGenRequest): Boolean = true
        override fun generate(request: SourceGenRequest): SourceGenResult {
            runs.incrementAndGet()
            val f = request.outputDir.resolve("com/gen/Greeting.java")
            Files.createDirectories(f.parent)
            Files.writeString(f, "package com.gen;\npublic class Greeting { public static String greeting() { return \"GENERATED-HI\"; } }\n")
            return SourceGenResult.OK
        }
    }

    private fun buildWorkspace(dir: Path, platform: PlatformCore): Project {
        ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
        val store: ProjectModelStore = ProjectModel.open(dir, platform, FacetCodecRegistry())
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        // The main source set carries a hand-written SOURCE root and a build-output GENERATED root.
        val main = SourceSetTemplate(
            "main", DependencyScope.IMPLEMENTATION,
            mapOf("src/main/java" to setOf(ContentRole.SOURCE), "build/generated" to setOf(ContentRole.GENERATED)),
        )
        store.workspace.projects.single().beginModification().apply {
            addModule("app", javaLib).addSourceSet(main); commit()
        }
        // Main references com.gen.Greeting, which only the generator produces.
        write(dir, "app/src/main/java/com/example/app/Main.java", MAIN)
        return store.workspace.projects.single()
    }

    @Test
    fun generatedSourceIsCompiledAndUsableByHandWrittenCode() {
        val dir = Files.createTempDirectory("srcgen")
        val platform = PlatformCore()
        val runs = AtomicInteger(0)
        try {
            val project = buildWorkspace(dir, platform)
            val build = JavaBuildSystem(generators = listOf(StubGenerator(runs)))
            val graph = build.createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.PACKAGE),
            )

            // The graph edge: compileJava must depend on generateSources (so generation happens first).
            val byName = graph.tasks.associateBy { it.name.value }
            val compileJava = byName[":app:compileJava"] ?: error("compileJava must be registered")
            assertTrue(":app:generateSources" in byName, "generateSources must be registered")
            assertTrue(
                graph.dependencies(compileJava).any { it.name.value == ":app:generateSources" },
                "compileJava must depend on generateSources",
            )

            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
            val log = StringBuilder()
            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "build failed:\n$log")
            assertEquals(1, runs.get(), "the generator must have run once")

            // The generated source was written into the GENERATED root and compiled into the app jar.
            assertTrue(Files.exists(dir.resolve("app/build/generated/com/gen/Greeting.java")), "generated source missing")
            val jar = jarPath(project.modules.single())
            assertTrue(Files.exists(jar), "missing jar: $jar")
            assertEquals("GENERATED-HI", runJava(listOf(jar), "com.example.app.Main"), "the generated code must run")

            // Nothing changed → the whole graph (generateSources included) is up-to-date; the generator does not re-run.
            val again = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }
            assertTrue(again.ranTasks.isEmpty(), "rebuild must be up-to-date, ran=${again.ranTasks.map { it.value }}")
            assertEquals(1, runs.get(), "the generator must NOT re-run on an unchanged rebuild")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private fun runJava(classpath: List<Path>, mainClass: String): String {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val proc = ProcessBuilder(javaBin, "-cp", classpath.joinToString(File.pathSeparator), mainClass)
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    private companion object {
        val MAIN = """
            package com.example.app;
            import com.gen.Greeting;
            public class Main {
                public static void main(String[] args) { System.out.println(Greeting.greeting()); }
            }
        """
    }
}
