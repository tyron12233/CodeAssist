package dev.ide.android.support

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.JavaCompileResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.TaskStatus
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The build DAG carries AGP-style per-module tasks: a library has `compileJava`, `processResources`,
 * `classes` (lifecycle) and `jar`; the app has `dexBuilderDebug` … and a top-level `assembleDebug`. This
 * also exercises the engine statuses the build console surfaces: a resource-less library `processResources`
 * reports NO-SOURCE, while `jar`/`classes` run.
 */
class AndroidAgpTasksTest {

    private object JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    @Test
    fun libraryModulesHaveAgpTasksAndStatuses() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-agp")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            types.register(JavaLib, PluginId("java-support"))
            AndroidSupport.register(types, FacetCodecRegistry())
            val appType = types.resolve("android-app")

            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("core", JavaLib).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))))
                }
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(ModuleDependency(ModuleId("core"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            write(dir, "core/src/main/java/com/example/core/Greeter.java", "package com.example.core; public class Greeter { public static String hi() { return \"hi\"; } }")
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val graph = AndroidBuildSystem.inProcess(jdtCompile(), sdk, signing).createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val names = graph.tasks.map { it.name.value }.toSet()
            assertTrue(
                names.containsAll(listOf(":core:compileJava", ":core:processResources", ":core:classes", ":core:jar", ":app:dexBuilderDebug", ":app:assembleDebug")),
                "expected AGP-style task set, got: ${names.sorted()}",
            )
            // The app dexes each module's JAR (not its class dir): the single dexBuilder consumes `:core:jar`,
            // and the sub-module archive is combined by mergeLibDex (no mergeExtDex — there are no external libs).
            val dexBuilder = graph.tasks.first { it.name.value == ":app:dexBuilderDebug" }
            assertTrue(
                graph.dependencies(dexBuilder).any { it.name.value == ":core:jar" },
                "the app's dexBuilder must consume :core:jar, deps=${graph.dependencies(dexBuilder).map { it.name.value }}",
            )
            assertTrue(":app:mergeLibDexDebug" in names, "a sub-module dependency must produce mergeLibDex, got: ${names.sorted()}")

            val events = ConcurrentHashMap<String, TaskStatus>()
            val exec = TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")), onEvent = { n, s -> events[n.value] = s })
            val log = StringBuilder()
            val outcome = runBlocking { exec.execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "build failed:\n$log")

            assertEquals(TaskStatus.NoSource, events[":core:processResources"], "a resource-less library processResources is NO-SOURCE")
            assertEquals(TaskStatus.Succeeded, events[":core:jar"], "the library jar should be produced")
            assertEquals(TaskStatus.Succeeded, events[":core:classes"], "the classes lifecycle runs the first time")
            assertTrue(Files.isRegularFile(dir.resolve("core/build/libs/core.jar")), "library jar artifact missing")

            // Re-build: nothing changed → classes/jar are up-to-date, processResources stays NO-SOURCE.
            val events2 = ConcurrentHashMap<String, TaskStatus>()
            runBlocking { TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")), onEvent = { n, s -> events2[n.value] = s }).execute(graph, SimpleTaskContext(), 2) }
            assertEquals(TaskStatus.UpToDate, events2[":core:classes"], "unchanged classes lifecycle is up-to-date")
            assertEquals(TaskStatus.UpToDate, events2[":core:jar"], "unchanged jar is up-to-date")
            assertEquals(TaskStatus.NoSource, events2[":core:processResources"], "still NO-SOURCE")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun jdtCompile(): JavaCompile = JavaCompile { sources, classpath, out, level ->
        val r = JdtBatchCompiler.compile(sources, classpath, out, level)
        JavaCompileResult(r.success, r.messages)
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            import com.example.core.Greeter;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) { super.onCreate(b); String s = Greeter.hi(); }
            }
        """
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"><activity android:name=".MainActivity" android:exported="true"/></application>
            </manifest>
        """
        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">Agp</string></resources>
        """
    }
}
