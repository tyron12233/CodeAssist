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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end guard for the reported failure: a launcher icon present as BOTH `drawable/ic_launcher.png` and
 * `drawable/ic_launcher.xml` (the frequent "added a vector, left the old raster" case) used to reach aapt2 as
 * two files for one resource and fail link with "resource 'drawable/ic_launcher' has a conflicting value" —
 * which no source edit could clear because the merge kept both. `mergeResources` now overlays file resources
 * by resource identity (higher priority wins, extension-independent, AGP-faithful), so exactly one survives
 * and the build succeeds. Runs the real native pipeline; skipped without an SDK.
 */
class AndroidFileResourceConflictTest {

    @Test
    fun sameResourceInTwoFileFormatsDoesNotBreakTheBuild() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-fileresconflict")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
            // drawable/ic_launcher declared as BOTH a raster and a vector — one resource, two files.
            writeBytes(dir, "app/src/main/res/drawable/ic_launcher.png", validPng())
            write(dir, "app/src/main/res/drawable/ic_launcher.xml", VECTOR)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.subprocess(sdk, signing) // the wiring the IDE uses
            val cache = BuildCache(dir.resolve(".caches/build"))
            val graph = buildSystem.createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val ok = runBlocking { TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(), 2) }.succeeded
            assertTrue(ok, "two files for one resource must collapse to one (higher priority wins), not fail aapt2")

            // Exactly one ic_launcher.* survives in the merged tree.
            val mergedDrawable = dir.resolve("app/build/intermediates/android/debug/merged-res/drawable")
            val launchers = Files.list(mergedDrawable).use { s ->
                s.map { it.fileName.toString() }.filter { it.startsWith("ic_launcher.") }.sorted().toList()
            }
            assertEquals(1, launchers.size, "exactly one ic_launcher file in merged-res; got $launchers")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private fun writeBytes(root: Path, rel: String, content: ByteArray) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.write(f, content)
    }

    /** A genuinely valid 8x8 PNG (ImageIO), so aapt2 accepts it (a hand-rolled byte blob can crash aapt2). */
    private fun validPng(): ByteArray {
        val img = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val bos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name" android:icon="@drawable/ic_launcher"><activity android:name=".MainActivity" android:exported="true"/></application>
            </manifest>
        """
        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">File Res</string></resources>
        """
        val VECTOR = """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
                <path android:fillColor="#FF0000" android:pathData="M0,0h24v24h-24z"/>
            </vector>
        """
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) { super.onCreate(b); }
            }
        """
    }
}
