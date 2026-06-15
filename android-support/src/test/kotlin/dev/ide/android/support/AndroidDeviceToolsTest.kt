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
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the on-device build wiring added for mobile assembly: [AndroidSdk.forDevice] (native tools
 * resolved from the app's `nativeLibraryDir` as `lib*.so`) and the [dev.ide.android.support.tools.ApksigSigner]
 * fallback that signs unaligned when no `zipalign` binary is present. The device app uses
 * `AndroidBuildSystem.inProcess` over exactly such an `AndroidSdk`.
 */
class AndroidDeviceToolsTest {

    /** `forDevice` points aapt2/zipalign at `nativeLibraryDir/lib*.so` and reports readiness off those. */
    @Test
    fun forDeviceMapsNativeToolsIntoNativeLibDir() {
        val dir = Files.createTempDirectory("device-sdk")
        try {
            val nativeLibDir = dir.resolve("lib/arm64-v8a").also { Files.createDirectories(it) }
            val androidJar = dir.resolve("android.jar").also { Files.writeString(it, "stub") }
            val sdk = AndroidSdk.forDevice(androidJar, nativeLibDir)

            assertEquals(nativeLibDir.resolve("libaapt2.so"), sdk.aapt2)
            assertEquals(nativeLibDir.resolve("libzipalign.so"), sdk.zipalign)
            assertEquals(nativeLibDir, sdk.buildToolsDir)

            // No aapt2 on disk yet → not ready; create it → ready (only androidJar + aapt2 are required).
            assertFalse(sdk.hasNativeTools(), "should not be ready before libaapt2.so exists")
            Files.writeString(sdk.aapt2, "fake-elf")
            assertTrue(sdk.hasNativeTools(), "androidJar + libaapt2.so present → ready")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The on-device dex/sign path with a missing zipalign: build a real one-module APK via
     * `inProcess` over an `AndroidSdk` whose `zipalign` path does not exist (the real native aapt2 is kept so
     * resources still compile). Asserts a valid, v1-signed APK is still produced and the signer logged the
     * unaligned fallback. Skipped (not failed) without an installed SDK.
     */
    @Test
    fun inProcessSignsUnalignedWhenZipalignMissing() {
        val detected = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(detected != null && detected.isComplete(), "Android SDK not installed; skipping")
        detected!!

        val dir = Files.createTempDirectory("device-apk")
        val platform = PlatformCore()
        try {
            // Real aapt2 (so resources/R compile), but a zipalign path that intentionally does not exist.
            val absentZipalign = dir.resolve("no-such-zipalign")
            val deviceLikeSdk = AndroidSdk(
                androidJar = detected.androidJar,
                buildToolsDir = detected.buildToolsDir,
                aapt2 = detected.aapt2,
                zipalign = absentZipalign,
            )
            assertFalse(Files.exists(absentZipalign))

            val store = buildAppWorkspace(dir, platform)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), detected.keytool)

            val buildSystem = AndroidBuildSystem.inProcess(jdtCompile(), deviceLikeSdk, signing)
            val graph = buildSystem.createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "APK build failed:\n$log")
            assertTrue(log.contains("zipalign unavailable"), "expected unaligned-sign fallback in log:\n$log")

            val apk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            assertTrue(Files.isRegularFile(apk), "signed APK missing at $apk\n$log")
            val entries = ZipFile(apk.toFile()).use { z -> z.entries().toList().map { it.name }.toSet() }
            assertTrue("classes.dex" in entries, "APK lacks classes.dex: $entries")
            assertTrue(entries.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".SF")) },
                "APK is not v1-signed: $entries")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun jdtCompile(): JavaCompile = JavaCompile { sources, classpath, out, level ->
        val r = JdtBatchCompiler.compile(sources, classpath, out, level)
        JavaCompileResult(r.success, r.messages)
    }

    private fun buildAppWorkspace(dir: Path, platform: PlatformCore): ProjectModelStore {
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
        ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
        val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")

        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", appType).apply {
                languageLevel = LanguageLevel.JAVA_17
                putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34, isApplication = true))
            }
            commit()
        }
        write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
        write(dir, "app/src/main/res/values/strings.xml", STRINGS)
        write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
        return store
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """

        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">CodeAssist Device Demo</string>
            </resources>
        """

        val ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView tv = new TextView(this);
                    tv.setText(R.string.app_name);
                    setContentView(tv);
                }
            }
        """
    }
}
