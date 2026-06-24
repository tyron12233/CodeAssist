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
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `shrinkResources` drops resources unreachable from the (shrunken) code. A referenced raw resource must
 * survive with its content; an unreferenced one must be stripped from the APK. The build links proto
 * resources, R8 shrinks them in-process, and the result is converted back to binary for packaging.
 * SDK-gated.
 */
class AndroidResourceShrinkTest {

    @Test
    fun unusedResourceIsStrippedAndUsedOneSurvives() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-shrink-res")
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
                            buildTypes = listOf(
                                BuildType("release", debuggable = false, minifyEnabled = true, shrinkResources = true),
                            ),
                        ),
                    )
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            // Two raw file resources with unique content markers; only the used one is referenced from code.
            write(dir, "app/src/main/res/raw/used_blob.txt", "USED_MARKER_${MARKER}_padding_${"u".repeat(2000)}")
            write(dir, "app/src/main/res/raw/unused_blob.txt", "UNUSED_MARKER_${MARKER}_padding_${"x".repeat(2000)}")
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("release"), BuildGoal.PACKAGE)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "shrinkResources build failed:\n$log")
            assertTrue(outcome.ranTasks.any { it.value == ":app:shrinkResourcesRelease" },
                "the resource-shrink/convert task must run: ${outcome.ranTasks.map { it.value }}")

            val apk = dir.resolve("app/build/outputs/apk/release/app-release.apk")
            assertTrue(Files.isRegularFile(apk), "signed release APK missing")
            // R8 emitted shrunk proto resources (converted back to binary for packaging).
            assertTrue(Files.isRegularFile(dir.resolve("app/build/intermediates/android/release/resources-proto-shrunk.ap_")),
                "R8 must emit shrunk proto resources")

            assertTrue(apkContainsBytes(apk, "USED_MARKER_$MARKER"), "the referenced raw resource must survive")
            assertFalse(apkContainsBytes(apk, "UNUSED_MARKER_$MARKER"), "the unreferenced raw resource must be shrunk away")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    /** Search every (decompressed) APK entry for [marker]; covers a resource whether stored or deflated. */
    private fun apkContainsBytes(apk: Path, marker: String): Boolean {
        val needle = marker.toByteArray(Charsets.UTF_8)
        ZipFile(apk.toFile()).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val bytes = zf.getInputStream(e.nextElement()).use { it.readBytes() }
                outer@ for (i in 0..bytes.size - needle.size) {
                    for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                    return true
                }
            }
        }
        return false
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        const val MARKER = "deadbeefcafe"
        // MainActivity references R.raw.used_blob (an inlined int the resource shrinker maps back to the resource).
        // Open the raw resource through the real API: this feeds R.raw.used_blob (the resource id) into
        // openRawResource(int) as a live argument, so the id survives in the dex and R8's resource shrinker
        // marks it reachable. (A bare `int x = R.raw.used_blob` is constant-folded away and looks unused.)
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    try { getResources().openRawResource(R.raw.used_blob).close(); } catch (Exception e) {}
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
            <resources><string name="app_name">Shrink</string></resources>
        """
    }
}
