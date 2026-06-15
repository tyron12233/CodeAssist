package dev.ide.build.engine

import dev.ide.bench.Direction
import dev.ide.bench.MetricUnit
import dev.ide.bench.RegressionSuite
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Build-at-scale regression suite (opt-in: `./gradlew :build-engine:regressionTest`).
 * It drives the IDE's own native build engine — the same `JavaBuildSystem` + incremental `TaskExecutorImpl`
 * wired behind the Run button — to compile, package, and run a large multi-module Java project, and verifies
 * the incremental engine scales:
 *
 *  - correctness at scale — a generated project of `core ← lib0..libN-1 ← app` (every lib depends on
 *    core, the app depends on every lib) compiles to jars and runs to the expected output.
 *  - incremental scales — re-running an unchanged build does zero work; editing one lib recompiles
 *    only that lib and the app (core and the other N-1 libs are skipped), independent of N. This is the
 *    "one input change re-runs only the affected subgraph" property at project scale, and the load-bearing
 *    assertion here — if fingerprinting/up-to-date checks regress, this count blows up.
 *
 * Metrics gate against `baselines/build-largeproject.json`: the incremental task count (tight, the real
 * signal), build correctness (a hard floor), and full-build wall time (loose + ceiling, machine-dependent).
 */
@Tag("regression")
class LargeBuildBenchmark {

    private val libCount = 12

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun mainSources() =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)))

    private fun javaBuildSystem() = JavaBuildSystem(JavaCompile { sources, classpath, out, level ->
        val r = JdtBatchCompiler.compile(sources, classpath, out, level)
        JavaCompileResult(r.success, r.messages)
    })

    /** Model + on-disk sources for `core ← lib0..libN-1 ← app`; returns the single project. */
    private fun buildWorkspace(dir: Path, platform: PlatformCore): Project {
        ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("core", javaLib).addSourceSet(mainSources())
            for (k in 0 until libCount) {
                addModule("lib$k", javaLib).apply {
                    addSourceSet(mainSources())
                    addDependency(ModuleDependency(ModuleId("core"), DependencyScope.API, exported = true))
                }
            }
            addModule("app", javaLib).apply {
                addSourceSet(mainSources())
                for (k in 0 until libCount) addDependency(ModuleDependency(ModuleId("lib$k"), DependencyScope.API, exported = true))
            }
            commit()
        }
        write(dir, "core/src/main/java/com/example/core/Core.java",
            "package com.example.core; public class Core { public int one() { return 1; } }")
        for (k in 0 until libCount) {
            write(dir, "lib$k/src/main/java/com/example/lib$k/Lib$k.java",
                "package com.example.lib$k; import com.example.core.Core; public class Lib$k { public int count() { return new Core().one(); } }")
        }
        write(dir, "app/src/main/java/com/example/app/Main.java", mainSource())
        return store.workspace.projects.single()
    }

    private fun mainSource(): String {
        val imports = (0 until libCount).joinToString("\n") { "import com.example.lib$it.Lib$it;" }
        val sums = (0 until libCount).joinToString(" + ") { "new Lib$it().count()" }
        return """
            package com.example.app;
            $imports
            public class Main {
                public static void main(String[] args) {
                    int total = $sums;
                    System.out.println("LARGE-BUILD-OK total=" + total);
                }
            }
        """.trimIndent()
    }

    @Test
    fun buildsAndRunsALargeMultiModuleProjectIncrementally() {
        val dir = Files.createTempDirectory("large-build")
        val platform = PlatformCore()
        try {
            val project = buildWorkspace(dir, platform)
            val moduleCount = project.modules.size // core + libs + app
            val graph = javaBuildSystem().createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.PACKAGE))
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))

            // 1) Full build (cold): the IDE engine compiles + packages every module. Time it.
            val log = StringBuilder()
            val t0 = System.nanoTime()
            val full = runBlocking { exec.execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 4) }
            val fullMs = (System.nanoTime() - t0) / 1_000_000.0
            assertTrue(full.succeeded, "large build failed:\n$log")

            // ... and it actually runs to the expected output, linking all $libCount libs + core.
            val jars = project.modules.map { jarPath(it) }
            jars.forEach { assertTrue(Files.exists(it), "missing jar: $it") }
            val output = runJava(jars, "com.example.app.Main")
            val runCorrect = "LARGE-BUILD-OK total=$libCount" in output

            // 2) No-op rebuild: an unchanged graph does zero work.
            val noop = runBlocking { exec.execute(graph, SimpleTaskContext(), 4) }
            val noopCorrect = noop.ranTasks.isEmpty()

            // 3) Edit ONE lib in the middle; only it + the app recompile (core + the other libs are skipped).
            val edited = libCount / 2
            write(dir, "lib$edited/src/main/java/com/example/lib$edited/Lib$edited.java",
                "package com.example.lib$edited; import com.example.core.Core; public class Lib$edited { public int count() { return new Core().one() + 0; } }")
            val incr = runBlocking { exec.execute(graph, SimpleTaskContext(), 4) }
            val ranValues = incr.ranTasks.map { it.value }
            // Task names are `:<module>:<task>` — reduce to the set of modules that recompiled.
            val ranModules = ranValues.map { it.removePrefix(":").substringBefore(":") }.toSet()
            val coreSkipped = "core" !in ranModules
            val otherLibSkipped = "lib${(edited + 1) % libCount}" !in ranModules
            val editedRecompiled = "lib$edited" in ranModules
            val appRecompiled = "app" in ranModules

            println("\n=== large build: $moduleCount modules (core + $libCount libs + app) ===")
            println("full build: %.0f ms, run output: %s".format(fullMs, output.trim()))
            println("incremental after 1-lib edit: ${incr.ranTasks.size} tasks ran ${ranValues.sorted()}")
            println("no-op rebuild ran ${noop.ranTasks.size} tasks\n")

            // Hard correctness assertions (deterministic — the engine's contract at scale).
            assertTrue(runCorrect, "app must build+run to the expected output, got: $output")
            assertTrue(noopCorrect, "unchanged rebuild must do zero work, ran=${noop.ranTasks.map { it.value }}")
            assertTrue(coreSkipped && otherLibSkipped, "a 1-lib edit must not recompile core or untouched libs: $ranValues")
            assertTrue(editedRecompiled && appRecompiled, "the edited lib and the app must recompile: $ranValues")

            val suite = RegressionSuite("build-largeproject")
            suite.quality("build.correct", if (runCorrect && noopCorrect) 1.0 else 0.0, tolerance = 0.0, floor = 1.0)
            // The scaling guard: a 1-lib edit recompiles a small, N-independent set (edited lib + app, jars).
            // The hard correctness above already pins *which* tasks; this tracks the count for trend, with a
            // ceiling at libCount/2 so a blow-up toward a full rebuild fails even if the baseline drifted.
            suite.metric("incremental.tasks", incr.ranTasks.size.toDouble(),
                Direction.LOWER_BETTER, MetricUnit.COUNT, tolerance = 0.5, bound = (libCount / 2).toDouble())
            // Raw compile throughput is very machine-dependent (and a broken incremental cache is already
            // caught deterministically by incremental.tasks above), so this is a wide trend metric with a
            // catastrophe ceiling — it only fails if a 14-module build somehow takes minutes.
            suite.latencyNs("fullBuild.ns", fullMs * 1_000_000.0, tolerance = 9.0, ceilingNs = 180_000_000_000.0)
            suite.count("modules", moduleCount, tolerance = 0.0)
            suite.finishAndAssert()
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
}
