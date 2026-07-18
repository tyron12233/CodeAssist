package dev.ide.android.support

import dev.ide.android.support.tools.AndroidAppLogRuntime
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
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
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the debug-only app-log injection: when an [AndroidAppLogRuntime] is supplied, a DEBUG build weaves
 * the log-bridge `<provider>` into the linked manifest and dexes the runtime classes into the APK, while a
 * RELEASE build (debuggable = false) is left byte-for-byte untouched even though the same runtime is supplied.
 *
 * SDK-gated (skipped, not failed, when no Android SDK is installed) and requires the compiled `:applog-runtime`
 * jar handed in via `-Dapplog.runtime.jar` (wired by the build script).
 */
class AppLogInjectTest {

    @Test
    fun instrumentsDebugButNeverRelease() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK (platform + build-tools) not installed; skipping")
        sdk!!
        val runtimeJar = resolveRuntimeJar()
        assumeTrue(runtimeJar != null, "applog-runtime jar not provided (-Dapplog.runtime.jar); skipping")
        runtimeJar!!

        val runtime = AndroidAppLogRuntime(
            runtimeJar,
            AndroidAppLogRuntime.DEFAULT_PROVIDER_CLASS,
            AndroidAppLogRuntime.DEFAULT_AUTHORITY_SUFFIX,
        )
        val dir = Files.createTempDirectory("android-applog")
        val platform = PlatformCore()
        try {
            val store = buildAppWorkspace(dir, platform)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            // The runtime is supplied unconditionally; only the build type's debuggable flag decides injection.
            val buildSystem = AndroidBuildSystem.subprocess(sdk, signing, appLogRuntime = { runtime })
            val cache = BuildCache(dir.resolve(".caches/build"))

            // --- Debug: injected. ---
            buildVariant(buildSystem, project, "debug", cache)
            val debugApk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            val instrumented = dir.resolve("app/build/intermediates/android/debug/instrumented-manifest/AndroidManifest.xml")
            assertTrue(Files.isRegularFile(instrumented), "debug build should produce an instrumented manifest")
            val manifestXml = Files.readString(instrumented)
            assertTrue(PROVIDER in manifestXml, "instrumented manifest lacks the log-bridge provider:\n$manifestXml")
            assertTrue("$APPLICATION_ID.${AndroidAppLogRuntime.DEFAULT_AUTHORITY_SUFFIX}" in manifestXml,
                "instrumented manifest lacks the per-app authority:\n$manifestXml")
            assertTrue(dexContainsType(debugApk, PROVIDER_DESC), "debug APK dex lacks $PROVIDER")

            // --- Release: never touched (debuggable = false), even with the runtime present. ---
            buildVariant(buildSystem, project, "release", cache)
            val releaseApk = dir.resolve("app/build/outputs/apk/release/app-release.apk")
            val relInstrumented = dir.resolve("app/build/intermediates/android/release/instrumented-manifest/AndroidManifest.xml")
            assertTrue(!Files.exists(relInstrumented), "release build must not instrument the manifest")
            assertTrue(!dexContainsType(releaseApk, PROVIDER_DESC), "release APK must not contain the log bridge")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun buildVariant(buildSystem: AndroidBuildSystem, project: dev.ide.model.Project, variant: String, cache: BuildCache) {
        val graph = buildSystem.createBuildGraph(
            project, BuildRequest(listOf(ModuleId("app")), VariantSelector(variant), BuildGoal.PACKAGE),
        )
        val log = StringBuilder()
        val outcome = runBlocking { TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
        assertTrue(outcome.succeeded, "$variant APK build failed:\n$log")
    }

    private fun resolveRuntimeJar(): Path? =
        System.getProperty("applog.runtime.jar")?.split(File.pathSeparator)
            ?.map { it.trim() }?.firstOrNull { "applog-runtime" in it && it.endsWith(".jar") }
            ?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }

    /** True if any `classes*.dex` in [apk] contains the type [descriptor] (dex stores it verbatim as a string). */
    private fun dexContainsType(apk: Path, descriptor: String): Boolean {
        if (!Files.isRegularFile(apk)) return false
        val needle = descriptor.toByteArray(Charsets.UTF_8)
        ZipFile(apk.toFile()).use { zf ->
            val dexEntries = zf.entries().toList().filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
            for (e in dexEntries) {
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                if (indexOf(bytes, needle) >= 0) return true
            }
        }
        return false
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun buildAppWorkspace(dir: Path, platform: PlatformCore): ProjectModelStore {
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
        ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
        val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")

        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", appType).apply {
                languageLevel = LanguageLevel.JAVA_17
                putFacet(
                    AndroidFacet(namespace = APPLICATION_ID, compileSdk = 34, minSdk = 24, targetSdk = 34, isApplication = true),
                )
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
        const val APPLICATION_ID = "com.example.app"
        const val PROVIDER = "dev.ide.applog.IdeLogBridgeProvider"
        const val PROVIDER_DESC = "Ldev/ide/applog/IdeLogBridgeProvider;"

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
                <string name="app_name">CodeAssist Demo</string>
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
