package dev.ide.build.engine

import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.lang.jdt.compile.JdtBatchCompiler
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Locks the **graph shape** of Kotlin compilation in the Java build system without needing the real
 * compiler: a stub [KotlinCompile] just drops a `.class` into the output dir. We assert `compileKotlin` is
 * registered, runs *before* `compileJava`, its output is packaged into the module jar, and the dex-run path
 * routes that output onto the runtime classpath. (Real Kotlin/Java interop codegen is proven end-to-end by
 * `KotlinJavaInteropBuildTest` in `:ide-core`, which has the actual K2 compiler.)
 */
class KotlinBuildWiringTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    /** Stub Kotlin compiler: emits one placeholder `.class` so packaging/classpath wiring is observable. */
    private fun stubKotlin(marker: MutableList<Path> = mutableListOf()) = KotlinCompile { kt, _, _, out, _ ->
        marker += kt
        Files.createDirectories(out.resolve("com/example/k"))
        Files.write(out.resolve("com/example/k/KGen.class"), byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        KotlinCompileResult(true)
    }

    private fun javaCompile() = JavaCompile { sources, classpath, out, level ->
        val r = JdtBatchCompiler.compile(sources, classpath, out, level)
        JavaCompileResult(r.success, r.messages)
    }

    private fun buildWorkspace(dir: Path, platform: PlatformCore): Pair<ProjectModelStore, Project> {
        ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        val mixed = SourceSetTemplate(
            "main", DependencyScope.IMPLEMENTATION,
            mapOf("src/main/java" to setOf(ContentRole.SOURCE), "src/main/kotlin" to setOf(ContentRole.SOURCE)),
        )
        store.workspace.projects.single().beginModification().apply {
            addModule("app", javaLib).addSourceSet(mixed); commit()
        }
        write(dir, "app/src/main/java/com/example/Main.java", MAIN)
        write(dir, "app/src/main/kotlin/com/example/Helper.kt", "package com.example.k\nclass Helper")
        return store to store.workspace.projects.single()
    }

    @Test
    fun compileKotlinRunsBeforeJavaAndIsPackaged() {
        val dir = Files.createTempDirectory("kt-wiring")
        val platform = PlatformCore()
        try {
            val (_, project) = buildWorkspace(dir, platform)
            val build = JavaBuildSystem(javaCompile(), stubKotlin())
            val graph = build.createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.PACKAGE),
            )
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }
            assertTrue(outcome.succeeded, "build failed")

            val ran = outcome.ranTasks.map { it.value }
            assertTrue(":app:compileKotlin" in ran, "compileKotlin must run: $ran")
            assertTrue(ran.indexOf(":app:compileKotlin") < ran.indexOf(":app:compileJava"),
                "compileKotlin must precede compileJava: $ran")

            val jar = jarPath(project.modules.single())
            val names = JarFile(jar.toFile()).use { jf -> jf.entries().toList().map { it.name } }
            assertTrue("com/example/k/KGen.class" in names, "Kotlin output must be packaged: $names")
            assertTrue("com/example/Main.class" in names, "Java output must be packaged: $names")

            val again = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }
            assertTrue(again.ranTasks.isEmpty(), "rebuild must be up-to-date: ${again.ranTasks.map { it.value }}")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun dexRunRoutesKotlinOutputOntoTheRuntimeClasspath() {
        val dir = Files.createTempDirectory("kt-dexrun")
        val platform = PlatformCore()
        try {
            val (_, project) = buildWorkspace(dir, platform)
            val app = project.modules.single()
            var dexedInputs: List<Path> = emptyList()
            val dexBackend = DexBackend { inputs, _, outDir ->
                dexedInputs = inputs
                Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(0))
                DexResult(true)
            }
            val dexRunner = object : DexRunner {
                override suspend fun run(dexDir: Path, mainClass: String, args: List<String>, log: (String) -> Unit) = 0
            }
            val build = JavaBuildSystem(javaCompile(), stubKotlin())
            val graph = build.createDexRunGraph(project, app, "com.example.Main", minApi = 21, dexBackend, dexRunner)
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(), 2) }
            assertTrue(outcome.succeeded, "dex-run failed")

            // The staged dex inputs (one jar per runtime-classpath dir) must include the Kotlin output class.
            val hasKotlin = dexedInputs.any { jar ->
                ZipFile(jar.toFile()).use { zf -> zf.entries().toList().any { it.name == "com/example/k/KGen.class" } }
            }
            assertTrue(hasKotlin, "the Kotlin output must reach the dex-run classpath; staged=${dexedInputs.map { it.fileName }}")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content)
    }

    private companion object {
        val MAIN = "package com.example;\npublic class Main { public static void main(String[] a) {} }"
    }
}
