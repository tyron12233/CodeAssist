package dev.ide.android.support

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.SigningConfig
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives [AndroidBuildSystem] to a signed Android App Bundle (`.aab`) with the real toolchain: aapt2 proto
 * linking, the JDT compiler, D8, the bundletool base-module assembly + `build-bundle`, and JAR (v1) signing.
 * Asserts the AAB is structurally a bundle (`BundleConfig.pb` + `base/manifest/AndroidManifest.xml` +
 * `base/resources.pb` + `base/dex/classes.dex`) and v1-signed, and that a no-op rebuild is up-to-date.
 *
 * Runs for both wirings: `subprocess` (jarsigner) and `inProcess` (apksig v1) — the latter is the on-device
 * signing path. Skipped (not failed) when no Android SDK is installed.
 */
class AndroidAabBuildTest {

    @Test
    fun buildsAndSignsAabWithSubprocessTools() = buildAndVerify { sdk, signing ->
        AndroidBuildSystem.subprocess(sdk, signing)
    }

    @Test
    fun buildsAndSignsAabWithInProcessTools() = buildAndVerify { sdk, signing ->
        AndroidBuildSystem.inProcess(sdk, signing)
    }

    private fun buildAndVerify(makeBuildSystem: (AndroidSdk, SigningConfig) -> AndroidBuildSystem) {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK (platform + build-tools) not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-aab")
        val platform = PlatformCore()
        try {
            val store = buildAppWorkspace(dir, platform)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)

            val buildSystem = makeBuildSystem(sdk, signing)
            val graph = buildSystem.createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.BUNDLE),
            )
            val cache = BuildCache(dir.resolve(".caches/build"))
            val log = StringBuilder()
            val outcome = runBlocking { TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "AAB build failed:\n$log")

            val aab = dir.resolve("app/build/outputs/bundle/debug/app-debug.aab")
            assertTrue(Files.isRegularFile(aab), "signed AAB missing at $aab\n$log")
            val entries = ZipFile(aab.toFile()).use { it.entries().toList().map { e -> e.name }.toSet() }
            assertTrue("BundleConfig.pb" in entries, "AAB lacks BundleConfig.pb: $entries")
            assertTrue("base/manifest/AndroidManifest.xml" in entries, "AAB lacks base manifest: $entries")
            assertTrue("base/resources.pb" in entries, "AAB lacks base/resources.pb: $entries")
            assertTrue("base/dex/classes.dex" in entries, "AAB lacks base/dex/classes.dex: $entries")
            assertTrue(entries.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".SF")) },
                "AAB is not v1-signed (no META-INF signature): $entries")

            val again = runBlocking { TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(), 2) }
            assertTrue(again.ranTasks.isEmpty(), "rebuild should do no work, ran=${again.ranTasks.map { it.value }}")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
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
