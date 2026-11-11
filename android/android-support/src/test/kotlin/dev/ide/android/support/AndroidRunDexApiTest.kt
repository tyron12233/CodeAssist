package dev.ide.android.support

import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.engine.DefaultBuildEnv
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleId
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Run is installed on the device that built it, so its dex is made for that device rather than for the
 * project's minSdk, which is what lets a minSdk 23 project use the prebuilt libraries. An Assemble, whose APK can
 * go anywhere, keeps the minSdk, and must never pick up dex a Run left behind.
 */
class AndroidRunDexApiTest {

    @Test
    fun onlyADebugBuildBelowTheFloorHeadedForADeviceIsDexedForIt() {
        val sdk = assumeAndroidSdk()
        testEnv("android-dexapi-rule") { env ->
            val build = AndroidBuildSystem.inProcess(
                sdk, DebugKeystore.getOrCreate(env.dir.resolve(".keystore/debug.ks"), sdk.keytool), localDeviceApi = 34,
            )
            fun facet(minSdk: Int, desugar: Boolean = false) =
                AndroidFacet(namespace = "a.b", compileSdk = 34, minSdk = minSdk, targetSdk = 34, coreLibraryDesugaringEnabled = desugar)
            assertEquals(26, build.dexApiFor(facet(23), "debug", 34), "a Run of a minSdk 23 debug build")
            assertEquals(26, build.dexApiFor(facet(21), "debug", 26))
            assertEquals(23, build.dexApiFor(facet(23), "debug", null), "no device: an Assemble, or a desktop")
            assertEquals(23, build.dexApiFor(facet(23), "release", 34), "a release build may be installed anywhere")
            assertEquals(23, build.dexApiFor(facet(23), "debug", 25), "a device below the floor gains nothing")
            assertEquals(28, build.dexApiFor(facet(28), "debug", 34), "already above the floor: unchanged")
            assertEquals(23, build.dexApiFor(facet(23, desugar = true), "debug", 34), "core-library desugaring keys the dex anyway")
        }
    }

    @Test
    fun aRunDexesForTheDeviceAndAnAssembleAfterItStillDexesForTheMinSdk() {
        val sdk = assumeAndroidSdk()
        testEnv("android-dexapi") { env ->
            val dir = env.dir
            val platform = env.platform
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 23, targetSdk = 34))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", MANIFEST)
            dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
            dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing, bootClasspath = listOf(sdk.androidJar), localDeviceApi = 34)
            val project = store.workspace.projects.single()
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.ASSEMBLE)
            val cache = BuildCache(dir.resolve(".caches/build"))
            val log = StringBuilder()
            fun ctx() = BuildContext(env = DefaultBuildEnv(dir, bootClasspathFor = { listOf(sdk.androidJar) }))
            fun execute(run: Boolean) = runBlocking {
                log.setLength(0)
                val graph = if (run) buildSystem.createRunGraph(project, request, ctx()) else buildSystem.createBuildGraph(project, request, ctx())
                TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            val inter = dir.resolve("app/build/intermediates/android/debug")
            val apk = AndroidBuildSystem.signedApkPath(project.modules.single(), "debug")

            assertTrue(execute(run = true).succeeded, "the Run build failed:\n$log")
            assertEquals("038", dexVersion(apk), "a Run's dex is made for API 26, the first with dex version 038")
            assertTrue(Files.isDirectory(inter.resolve("dex-archives-api26/project")), "device dex has directories of its own")
            assertFalse(Files.exists(inter.resolve("dex-archives")), "and writes nothing where an Assemble's dex goes")
            assertEquals(false to true, verifiedSignatureSchemes(apk, 26), "a Run's APK, for API 26 only, carries no v1 signature")

            assertTrue(execute(run = false).succeeded, "the Assemble build failed:\n$log")
            assertEquals("035", dexVersion(apk), "an Assemble after a Run is dexed for the minSdk, not left with the Run's dex")
            assertTrue(Files.isDirectory(inter.resolve("dex-archives/project")))
            assertEquals(true to true, verifiedSignatureSchemes(apk, 23), "an Assemble for minSdk 23 is v1-signed too: API 23 reads nothing else")

            assertTrue(execute(run = true).succeeded, "the second Run build failed:\n$log")
            assertEquals("038", dexVersion(apk), "and a Run after that is the device's again")
        }
    }

    /** The version in the first dex file's header: `dex\n035\0` and so on. */
    private fun dexVersion(apk: Path): String = ZipFile(apk.toFile()).use { zip ->
        zip.getInputStream(zip.getEntry("classes.dex")).use { String(it.readNBytes(8), 4, 3, Charsets.US_ASCII) }
    }

    private companion object {
        // A lambda and a default interface method: both are desugared below API 24 and neither is at 26.
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                interface Greeter { default String greet() { return "hi"; } }
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    Runnable r = () -> setTitle(new Greeter() {}.greet());
                    r.run();
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
            <resources><string name="app_name">DexApi</string></resources>
        """
    }
}
