package dev.ide.core

import dev.ide.android.support.AndroidAppModuleType
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.JavaCompileResult
import dev.ide.build.engine.KotlinCompile
import dev.ide.build.engine.KotlinCompileResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
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
import kotlin.test.assertTrue

/**
 * Reproduces the Compose-template shape: a **pure-Kotlin** android-app whose entry `MainActivity` is a
 * `.kt` file and which has **zero `.java` sources**. The device failure was a launch-time
 * `ClassNotFoundException` for the activity — i.e. the Kotlin activity never made it into the dex/APK.
 * This asserts the Kotlin activity is both dexed (project scope) and present in `classes.dex` of the APK.
 * Needs an installed SDK; skipped otherwise.
 */
class AndroidPureKotlinActivityTest {

    @Test
    fun pureKotlinActivityIsDexedAndPackaged() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-pure-kotlin")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("kotlin-stdlib")
                .apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(stdlibJar())); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("kotlin-stdlib"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            // No .java anywhere — the activity itself is Kotlin (the Compose-template shape).
            write(dir, "app/src/main/kotlin/com/example/app/MainActivity.kt", ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val build = AndroidBuildSystem.subprocess(jdtCompile(sdk.androidJar), sdk, signing, kotlinCompile(sdk.androidJar))
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(build.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "pure-kotlin android build failed:\n$log")

            val ran = outcome.ranTasks.map { it.value }
            assertTrue(":app:compileKotlinDebug" in ran, "compileKotlin must run for a pure-Kotlin app: $ran\n$log")

            val activityDex = dir.resolve("app/build/intermediates/android/debug/dex-archives/project/com/example/app/MainActivity.dex")
            assertTrue(Files.isRegularFile(activityDex), "Kotlin activity not dexed into project scope: $activityDex\n$log")

            val apk = AndroidBuildSystem.signedApkPath(store.workspace.projects.single().modules.single(), "debug")
            assertTrue(Files.isRegularFile(apk), "signed APK missing: $apk\n$log")
            assertTrue(apkHasDex(apk), "APK has no classes.dex: $apk\n$log")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun apkHasDex(apk: Path): Boolean = ZipFile(apk.toFile()).use { zf ->
        zf.entries().asSequence().any { it.name.matches(Regex("classes\\d*\\.dex")) }
    }

    private fun stdlibJar(): Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    private fun jdtCompile(androidJar: Path): JavaCompile = JavaCompile { sources, classpath, out, level ->
        val boot = if (level == "8") listOf(androidJar) else emptyList()
        val r = JdtBatchCompiler.compile(sources, classpath, out, level, bootClasspath = boot)
        JavaCompileResult(r.success, r.messages)
    }

    private fun kotlinCompile(androidJar: Path): KotlinCompile = KotlinCompile { kt, java, cp, out, target ->
        val r = KotlinJvmCompiler().compile(kt, java, cp, out, target, bootClasspath = listOf(androidJar))
        KotlinCompileResult(r.success, r.messages)
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val ACTIVITY = """
            package com.example.app
            import android.app.Activity
            import android.os.Bundle
            import android.widget.TextView
            class MainActivity : Activity() {
                override fun onCreate(b: Bundle?) {
                    super.onCreate(b)
                    val tv = TextView(this)
                    tv.text = "hello from kotlin activity"
                    setContentView(tv)
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
            <resources><string name="app_name">KotlinApp</string></resources>
        """
    }
}
