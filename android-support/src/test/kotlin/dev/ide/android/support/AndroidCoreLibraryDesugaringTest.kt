package dev.ide.android.support

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.DesugarLib
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleId
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Core-library desugaring: an app using `java.time` at minSdk 21 (below java.time's native API 26) must get
 * the desugared `j$` runtime L8-compiled into the APK on BOTH paths - the D8 debug build and the R8 release
 * build. SDK-gated, and also skipped when the desugar artifacts are not on the test classpath.
 */
class AndroidCoreLibraryDesugaringTest {

    @Test
    fun debugBuildDesugarsAndPackagesTheRuntime() = buildAndAssertDesugarRuntime("debug", minify = false)

    @Test
    fun releaseBuildDesugarsAndPackagesTheRuntime() = buildAndAssertDesugarRuntime("release", minify = true)

    private fun buildAndAssertDesugarRuntime(variant: String, minify: Boolean) {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        val desugar = desugarLib()
        assumeTrue(desugar != null, "desugar_jdk_libs artifacts not on the test classpath; skipping")
        sdk!!; desugar!!

        val dir = Files.createTempDirectory("android-desugar-$variant")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    // Level 8: ecj's >=9 module system otherwise sees java.time in both the host JDK's java.base
                    // and android.jar ("accessible from more than one module") on the desktop host. Desugaring is
                    // gated on minSdk, not language level, so level 8 still exercises the java.time rewrite.
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(
                        AndroidFacet(
                            namespace = "com.example.app", compileSdk = 34, minSdk = 21, targetSdk = 34,
                            coreLibraryDesugaringEnabled = true,
                            buildTypes = listOf(
                                BuildType("debug", debuggable = true, minifyEnabled = false),
                                BuildType("release", debuggable = false, minifyEnabled = minify),
                            ),
                        ),
                    )
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing, desugarLib = desugar)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector(variant), BuildGoal.PACKAGE)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "$variant desugaring build failed:\n$log")
            assertTrue(outcome.ranTasks.any { it.value == ":app:l8DexDesugarLib${variant.replaceFirstChar { c -> c.uppercase() }}" },
                "the L8 desugar-runtime task must run: ${outcome.ranTasks.map { it.value }}")

            val apk = dir.resolve("app/build/outputs/apk/$variant/app-$variant.apk")
            assertTrue(Files.isRegularFile(apk), "signed $variant APK missing")
            // The desugared runtime classes live under the `j$` package; their presence proves both that the
            // app's java.time references were rewritten and that L8 dexed the runtime into the APK.
            assertTrue(dexContains(apkDexBytes(apk), "Lj$/time/"), "the desugared j\$ runtime must be packaged")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    /** Resolve the desugar runtime + config jars from the `desugar.lib.path` classpath the build script passes. */
    private fun desugarLib(): DesugarLib? {
        val path = System.getProperty("desugar.lib.path") ?: return null
        val jars = path.split(File.pathSeparator).map { Paths.get(it) }.filter { Files.exists(it) }
        val runtime = jars.firstOrNull { val n = it.fileName.toString(); "desugar_jdk_libs-" in n && "configuration" !in n }
        val config = jars.firstOrNull { "desugar_jdk_libs_configuration" in it.fileName.toString() }
        return if (runtime != null && config != null) DesugarLib(runtime, config) else null
    }

    private fun apkDexBytes(apk: Path): ByteArray {
        val out = ByteArrayOutputStream()
        ZipFile(apk.toFile()).use { zf ->
            zf.entries().asSequence().filter { it.name.matches(Regex("""classes\d*\.dex""")) }
                .forEach { e -> zf.getInputStream(e).use { it.copyTo(out) } }
        }
        return out.toByteArray()
    }

    private fun dexContains(dex: ByteArray, descriptor: String): Boolean {
        val needle = descriptor.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..dex.size - needle.size) {
            for (j in needle.indices) if (dex[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        // Uses java.time.LocalDate (native API 26); at minSdk 21 this forces core-library desugaring.
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    java.time.LocalDate today = java.time.LocalDate.now();
                    android.util.Log.i("app", "day=" + today.getDayOfMonth());
                }
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
            <resources><string name="app_name">Desugar</string></resources>
        """
    }
}
