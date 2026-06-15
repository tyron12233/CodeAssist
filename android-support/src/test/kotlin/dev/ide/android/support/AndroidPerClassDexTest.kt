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
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleId
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * dexBuilder archives the project per class file: editing one class re-dexes only it, leaving the
 * other classes' `.dex` byte-for-byte untouched (AGP's per-class incremental dexing). Proven by editing
 * one of two app classes and comparing the unedited class's per-class `.dex` across the two builds.
 */
class AndroidPerClassDexTest {

    @Test
    fun editingOneClassReDexesOnlyThatClass() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-perclass")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    // JAVA_8: compiling Android code against android.jar as the boot classpath needs compliance
                    // < 9 (ecj rejects -bootclasspath at ≥ 9), which sidesteps the JAVA_17 modular split-package
                    // conflict and is order-independent (matches the on-device level).
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            write(dir, "app/src/main/java/com/example/app/Helper.java", HELPER)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", activity("one"))

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(jdtCompile(sdk.androidJar), sdk, signing)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE)
            val cache = BuildCache(dir.resolve(".caches/build"))
            val log = StringBuilder()
            fun build() = runBlocking {
                log.setLength(0)
                TaskExecutorImpl(cache).execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }

            val classes = dir.resolve("app/build/intermediates/android/debug/classes/com/example/app")
            val perClass = dir.resolve("app/build/intermediates/android/debug/dex-archives/project/com/example/app")
            val helperDex = perClass.resolve("Helper.dex")
            val mainDex = perClass.resolve("MainActivity.dex")

            assertTrue(build().succeeded, "first build failed:\n$log")
            assertTrue(Files.isRegularFile(helperDex) && Files.isRegularFile(mainDex), "per-class .dex not produced: ${listing(perClass)}\n$log")
            val helperClassBefore = Files.readAllBytes(classes.resolve("Helper.class"))
            val helperBefore = Files.readAllBytes(helperDex)
            val mainBefore = Files.readAllBytes(mainDex)

            // Edit ONLY MainActivity.
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", activity("two"))
            assertTrue(build().succeeded, "incremental build failed")

            val helperClassStable = Files.readAllBytes(classes.resolve("Helper.class")).contentEquals(helperClassBefore)
            assertTrue(Files.readAllBytes(helperDex).contentEquals(helperBefore),
                "unedited Helper must NOT be re-dexed (its .dex changed); Helper.class stable across recompile=$helperClassStable")
            assertTrue(!Files.readAllBytes(mainDex).contentEquals(mainBefore), "edited MainActivity must be re-dexed (its .dex is unchanged)")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun listing(dir: Path): List<String> =
        if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() } else emptyList()

    // At compliance 8, compile against android.jar as the BOOT classpath (the on-device model) so java.* +
    // android.* resolve without the JAVA_17 modular split-package conflict. (-bootclasspath is rejected at ≥ 9.)
    private fun jdtCompile(androidJar: Path): JavaCompile = JavaCompile { sources, classpath, out, level ->
        val boot = if (level == "8") listOf(androidJar) else emptyList()
        val r = JdtBatchCompiler.compile(sources, classpath, out, level, bootClasspath = boot)
        JavaCompileResult(r.success, r.messages)
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private fun activity(tag: String) = """
        package com.example.app;
        import android.app.Activity;
        import android.os.Bundle;
        import android.widget.TextView;
        public class MainActivity extends Activity {
            @Override protected void onCreate(Bundle b) {
                super.onCreate(b);
                TextView tv = new TextView(this);
                tv.setText(Helper.text() + "$tag");
                setContentView(tv);
            }
        }
    """

    private companion object {
        val HELPER = "package com.example.app; public final class Helper { public static String text() { return \"hi\"; } }"
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"><activity android:name=".MainActivity" android:exported="true"/></application>
            </manifest>
        """
        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">PerClass</string></resources>
        """
    }
}
