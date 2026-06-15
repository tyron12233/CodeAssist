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
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `minSdk < 21` is mono-/legacy-multidex: AGP collapses the per-scope merges into one `mergeDex`
 * (MERGE_ALL). This proves the build picks that path — `mergeDex` is wired, the split `mergeProjectDex`/
 * `mergeLibDex`/`mergeExtDex` are not — and that the resulting APK has a single `classes.dex` carrying
 * both the app and its sub-module's classes.
 */
class AndroidMonoDexTest {

    private object JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    @Test
    fun minSdkBelow21CollapsesToASingleMergeDex() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-monodex")
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
                    // JAVA_8 so the app compiles against android.jar as the boot classpath (compliance < 9),
                    // sidestepping the JAVA_17 modular split-package conflict — reliable in any test order.
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 19, targetSdk = 34))
                    addDependency(ModuleDependency(ModuleId("core"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            write(dir, "core/src/main/java/com/example/core/Greeter.java", "package com.example.core; public class Greeter { public static String hi() { return \"hi\"; } }")
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val graph = AndroidBuildSystem.inProcess(jdtCompile(sdk.androidJar), sdk, signing).createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val names = graph.tasks.map { it.name.value }.toSet()
            assertTrue(":app:mergeDexDebug" in names, "minSdk<21 must use the MERGE_ALL mergeDex; got: ${names.sorted()}")
            assertTrue(":app:mergeProjectDexDebug" !in names, "the split project merge must NOT be used for mono-dex")
            assertTrue(":app:mergeLibDexDebug" !in names, "the split lib merge must NOT be used for mono-dex")

            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "mono-dex build failed:\n$log")

            val apk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            ZipFile(apk.toFile()).use { zf ->
                val dexEntries = zf.entries().toList().map { it.name }.filter { it.matches(Regex("classes\\d*\\.dex")) }
                assertTrue(dexEntries == listOf("classes.dex"), "mono-dex must be a single classes.dex, got: $dexEntries")
                val dex = zf.getInputStream(zf.getEntry("classes.dex")).readBytes().toString(Charsets.ISO_8859_1)
                assertTrue("Lcom/example/app/MainActivity;" in dex, "app class not in classes.dex")
                assertTrue("Lcom/example/core/Greeter;" in dex, "sub-module class not in classes.dex")
            }
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    // At compliance 8 (the android app), compile against android.jar as the boot classpath to dodge the
    // JAVA_17 modular split-package conflict; the plain java-lib (level 17) compiles against the host JDK.
    private fun jdtCompile(androidJar: Path): JavaCompile = JavaCompile { sources, classpath, out, level ->
        val boot = if (level == "8") listOf(androidJar) else emptyList()
        val r = JdtBatchCompiler.compile(sources, classpath, out, level, bootClasspath = boot)
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
            <resources><string name="app_name">Mono</string></resources>
        """
    }
}
