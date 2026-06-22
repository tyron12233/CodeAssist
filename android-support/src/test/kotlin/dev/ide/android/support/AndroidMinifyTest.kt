package dev.ide.android.support

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
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `minifyEnabled` release build dexes via the R8 path: the DAG runs `minifyReleaseWithR8` (replacing the
 * dexBuilder→merge tasks) and still produces a signed APK. R8 runs in pass-through here (no keep rules yet),
 * so it acts as the dexer while exercising the real R8 pipeline.
 */
class AndroidMinifyTest {

    @Test
    fun minifiedReleaseBuildsThroughR8() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-minify")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(
                        AndroidFacet(
                            namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34,
                            buildTypes = listOf(BuildType("release", debuggable = false, minifyEnabled = true)),
                        ),
                    )
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("release"), BuildGoal.PACKAGE)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "minified build failed:\n$log")
            assertTrue(outcome.ranTasks.any { it.value == ":app:minifyReleaseWithR8" }, "R8 minify task must run: ${outcome.ranTasks.map { it.value }}")
            assertTrue(Files.isRegularFile(dir.resolve("app/build/outputs/apk/release/app-release.apk")), "signed release APK missing")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    TextView tv = new TextView(this);
                    tv.setText("minified");
                    setContentView(tv);
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
            <resources><string name="app_name">Min</string></resources>
        """
    }
}
