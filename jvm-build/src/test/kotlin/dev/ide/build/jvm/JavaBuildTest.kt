package dev.ide.build.jvm

import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.DexBackend
import dev.ide.build.engine.DexResult
import dev.ide.build.engine.DexRunner
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.jarPath
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
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Native build + the console-app run task: builds `app → util → core` (api-exported) via the native
 * [JavaBuildSystem] on the JDT compile backend (lang-jdt's JdtCompileTask), then (1) packages to jars and
 * runs them, (2) runs the app through the `run` task (the Gradle `application`-plugin equivalent) which
 * always re-executes, and (3) exercises the on-device `createDexRunGraph` (`compileJava → dexRun → runDex`)
 * with fake dex ports.
 */
class JavaBuildTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun mainSources() =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)))

    // Java-only build: no boot classpath (ecj uses the host JRE on the desktop) and no Kotlin compiler.
    private fun javaBuildSystem() = JavaBuildSystem()

    /** Build the demo workspace (model + sources on disk) and return its single project. */
    private fun buildWorkspace(dir: Path, platform: PlatformCore): Pair<ProjectModelStore, Project> {
        ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("core", javaLib).addSourceSet(mainSources())
            addModule("util", javaLib).apply {
                addSourceSet(mainSources())
                addDependency(ModuleDependency(ModuleId("core"), DependencyScope.API, exported = true))
            }
            addModule("app", javaLib).apply {
                addSourceSet(mainSources())
                addDependency(ModuleDependency(ModuleId("util"), DependencyScope.API, exported = true))
            }
            commit()
        }
        write(dir, "core/src/main/java/com/example/core/Greeter.java", GREETER)
        write(dir, "util/src/main/java/com/example/util/Formatter.java", FORMATTER)
        write(dir, "app/src/main/java/com/example/app/Main.java", MAIN)
        return store to store.workspace.projects.single()
    }

    @Test
    fun buildsAndRunsAMultiModuleJavaCli() {
        val dir = Files.createTempDirectory("javabuild")
        val platform = PlatformCore()
        try {
            val (_, project) = buildWorkspace(dir, platform)
            val graph = javaBuildSystem().createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.PACKAGE),
            )
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
            val log = StringBuilder()
            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "build failed:\n$log")

            val jars = listOf("core", "util", "app").map { name -> jarPath(project.modules.first { it.name == name }) }
            jars.forEach { assertTrue(Files.exists(it), "missing jar: $it") }
            assertTrue("HELLO, WORLD!" in runJava(jars, "com.example.app.Main"))

            val again = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }
            assertTrue(again.ranTasks.isEmpty(), "re-build must be up-to-date, ran=${again.ranTasks.map { it.value }}")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runsConsoleAppViaTheRunTask() {
        val dir = Files.createTempDirectory("javarun")
        val platform = PlatformCore()
        try {
            val (_, project) = buildWorkspace(dir, platform)
            val app = project.modules.first { it.name == "app" }
            val graph = javaBuildSystem().createRunGraph(project, app, "com.example.app.Main")
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
            val log = StringBuilder()

            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "run failed:\n$log")
            assertTrue("HELLO, WORLD!" in log.toString(), "the run task should stream program output:\n$log")

            // The compiles are now up-to-date, but the `run` task must execute again (AlwaysRun).
            val again = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }
            assertTrue(again.ranTasks.any { it.value.endsWith(":run") }, "run must always execute: ${again.ranTasks.map { it.value }}")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runsConsoleAppViaTheDexRunPath() {
        val dir = Files.createTempDirectory("javadexrun")
        val platform = PlatformCore()
        try {
            val (_, project) = buildWorkspace(dir, platform)
            val app = project.modules.first { it.name == "app" }

            // Fake D8: record the runtime classpath it was handed, drop a placeholder classes.dex so the graph proceeds.
            var dexedInputs: List<Path> = emptyList()
            val dexBackend = DexBackend { inputs, _, outDir ->
                dexedInputs = inputs
                Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(0))
                DexResult(true, listOf("fake-dex ${inputs.size} input(s)"))
            }
            // Fake DexClassLoader runner: record what it was asked to run.
            var ranMain: String? = null
            var ranDexDir: Path? = null
            val dexRunner = object : DexRunner {
                override suspend fun run(dexDir: Path, mainClass: String, args: List<String>, log: (String) -> Unit): Int {
                    ranMain = mainClass; ranDexDir = dexDir
                    log("ran $mainClass from ${dexDir.fileName}")
                    return 0
                }
            }

            val graph = javaBuildSystem().createDexRunGraph(project, app, "com.example.app.Main", minApi = 21, dexBackend, dexRunner)
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
            val log = StringBuilder()
            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "dex-run failed:\n$log")

            val ran = outcome.ranTasks.map { it.value }
            assertTrue(":app:dexRun" in ran, "dexRun must run: $ran")
            assertTrue(":app:runDex" in ran, "runDex must run: $ran")
            assertTrue(dexedInputs.isNotEmpty(), "the dex backend must receive the runtime classpath (app + deps)")
            assertTrue(ranMain == "com.example.app.Main", "runner got the wrong main: $ranMain")
            assertTrue(ranDexDir?.let { Files.exists(it.resolve("classes.dex")) } == true, "runner must get the produced dex dir")

            // runDex is AlwaysRun → re-executes; the unchanged dexRun is up-to-date the second time.
            val again = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }.ranTasks.map { it.value }
            assertTrue(":app:runDex" in again, "runDex must always re-run: $again")
            assertTrue(":app:dexRun" !in again, "unchanged dexRun should be up-to-date: $again")
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
        val GREETER = """
            package com.example.core;
            public class Greeter {
                public String greet(String name) { return "Hello, " + name + "!"; }
            }
        """
        val FORMATTER = """
            package com.example.util;
            import com.example.core.Greeter;
            public class Formatter {
                private final Greeter greeter = new Greeter();
                public String format(String name) { return greeter.greet(name).toUpperCase(); }
            }
        """
        val MAIN = """
            package com.example.app;
            import com.example.util.Formatter;
            public class Main {
                public static void main(String[] args) {
                    System.out.println(new Formatter().format("World"));
                }
            }
        """
    }
}
