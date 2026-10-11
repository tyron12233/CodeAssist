package dev.ide.android.support

import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each Android module compiles against its own compileSdk's `android.jar` when [AndroidBuildSystem]'s
 * `platformFor` has one: a compileSdk-34 module calling an API-35 method fails against `android-34`, and the
 * same module builds when no platform for 34 is offered (the SDK's default, newer, platform). Needs an SDK
 * with `android-34` and a newer default platform; skipped otherwise.
 */
class AndroidCompileSdkPlatformTest {

    @Test
    fun aModuleCompilesAgainstItsCompileSdkPlatform() {
        val sdk = assumeAndroidSdk()
        val android34 = sdk.androidJar.parent.parent.resolve("android-34").resolve("android.jar")
        assumeTrue(Files.isRegularFile(android34), "platforms/android-34 not installed; skipping")
        assumeTrue(AndroidApiLevels.levelOfPlatformJar(sdk.androidJar) >= 35, "default platform is older than 35; skipping")

        fun compile(platformFor: (Int) -> Path?): Pair<Boolean, String> = testEnv("android-compile-sdk") { env ->
            val dir = env.dir
            val store = ProjectModel.open(dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(env.platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(env.platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", MANIFEST)
            dir.writeSource("app/src/main/java/com/example/app/Use.java", USE)
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val build = AndroidBuildSystem.subprocess(sdk, signing, platformFor = platformFor)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.COMPILE_ONLY)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(build.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            outcome.succeeded to log.toString()
        }

        val (on34, log34) = compile { level -> android34.takeIf { level == 34 } }
        assertFalse(on34, "an API-35 call must not compile against android-34:\n$log34")
        assertTrue("setRequestedFrameRate" in log34, log34)

        val (onDefault, logDefault) = compile { null }
        assertTrue(onDefault, "without a platform for 34 the module uses the SDK's default one:\n$logDefault")
    }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application/>
            </manifest>
        """.trimIndent()
        val USE = """
            package com.example.app;
            import android.view.View;
            public class Use {
                static void m(View v) { v.setRequestedFrameRate(60f); }
            }
        """.trimIndent()
    }
}
