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
 * A `proguard-rules.pro` referenced by a `minifyEnabled` build type but created only AFTER a first build is
 * picked up on the next (incremental) build: adding the keep file changes R8's `filePaths("keep", …)` input
 * fingerprint, so `minify<Variant>WithR8` re-runs and applies the new rules instead of staying up-to-date.
 * (Keep rules only apply when minify is on at all — a debug/non-minified build never runs R8.)
 */
class AndroidIncrementalProguardTest {

    @Test
    fun addingAReferencedKeepFileReRunsR8() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-incr-proguard")
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
                            buildTypes = listOf(BuildType("release", debuggable = false, minifyEnabled = true, proguardFiles = listOf("proguard-rules.pro"))),
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
            val cacheDir = dir.resolve(".caches/build")
            val r8 = ":app:minifyReleaseWithR8"

            fun build(): Set<String> {
                val log = StringBuilder()
                val outcome = runBlocking {
                    TaskExecutorImpl(BuildCache(cacheDir))
                        .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
                }
                assertTrue(outcome.succeeded, "build failed:\n$log")
                return outcome.ranTasks.map { it.value }.toSet()
            }

            // Build 1: proguard-rules.pro is referenced by the release build type but does not exist yet.
            assertTrue(r8 in build(), "R8 must run on the first minified build")

            // The user adds the keep file at the module root.
            write(dir, "app/proguard-rules.pro", "-keep class com.example.app.MainActivity { *; }")

            // Build 2 on the SAME cache: the newly-added keep file must force R8 to re-run (not up-to-date).
            assertTrue(r8 in build(), "adding a referenced keep-rule file must re-run R8 on the incremental build")
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
