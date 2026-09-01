package dev.ide.android.support.aidl

import dev.ide.android.support.AndroidAppModuleType
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.assumeAndroidSdk
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.TaskName
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
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `compileAidl` inside a real Android build: an app whose `src/main/aidl` holds a service interface, built
 * through the native pipeline with a hand-written Java `Service` that implements the generated `Stub`.
 *
 * The point is the wiring, not the generator ([AidlCompilerTest] covers that): that the task is registered
 * from nothing but the presence of `.aidl`, that its output reaches `compileJava` as a source root, that the
 * engine's up-to-date check holds across a rebuild, and (the one that would silently rot) that editing a
 * `.aidl` re-runs the generation *and* the compile that consumed it.
 */
class AndroidAidlBuildTest {

    @Test
    fun compilesAidlIntoTheAppAndRebuildsIncrementally() {
        val sdk = assumeAndroidSdk()
        testEnv("android-aidl") { env ->
            val dir = env.dir
            val store = appWithAidl(dir, env.platform)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            // The same bootclasspath a host wires ([BuildService]): `android.jar` + the desugar stubs. Without
            // it ecj resolves `java.lang` from both the host JDK's modules and android.jar and rejects the
            // clash at compliance 9+, which has nothing to do with AIDL.
            val buildSystem = AndroidBuildSystem.subprocess(sdk, signing, bootClasspath = bootClasspathOf(sdk))
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.COMPILE_ONLY)
            val cache = BuildCache(dir.resolve(".caches/build"))

            val log = StringBuilder()
            val first = runBlocking {
                TaskExecutorImpl(cache).execute(
                    buildSystem.createBuildGraph(project, request), SimpleTaskContext(log = { log.appendLine(it) }), 2,
                )
            }
            assertTrue(first.succeeded, "AIDL build failed:\n$log")

            // The generated Java landed in the variant's aidl gen root...
            val generated = dir.resolve("app/build/intermediates/android/debug/gen-aidl/com/example/app/IGreeter.java")
            assertTrue(Files.isRegularFile(generated), "compileAidl produced no Java at $generated\n$log")
            // ...and compiled, together with the service that extends its Stub.
            val classes = dir.resolve("app/build/intermediates/android/debug/classes/com/example/app")
            assertTrue(Files.isRegularFile(classes.resolve("IGreeter\$Stub.class")), "Stub was not compiled\n$log")
            assertTrue(Files.isRegularFile(classes.resolve("IGreeter\$Stub\$Proxy.class")), "Proxy was not compiled\n$log")
            assertTrue(Files.isRegularFile(classes.resolve("GreeterService.class")), "the service did not compile\n$log")

            val again = runBlocking {
                TaskExecutorImpl(cache).execute(buildSystem.createBuildGraph(project, request), SimpleTaskContext(), 2)
            }
            assertTrue(again.ranTasks.isEmpty(), "rebuild should do no work, ran=${again.ranTasks.map { it.value }}")

            // Editing the interface must re-run generation AND the compile that reads its output. An .aidl
            // edit that only re-ran generation would leave the app compiled against the previous signature.
            // A `const` keeps the service a valid implementation, so the rebuild is expected to succeed.
            val editLog = StringBuilder()
            dir.writeSource(
                "app/src/main/aidl/com/example/app/IGreeter.aidl",
                "package com.example.app;\ninterface IGreeter {\n  const int VERSION = 2;\n  String greet(String name);\n}\n",
            )
            val edited = runBlocking {
                TaskExecutorImpl(cache).execute(
                    buildSystem.createBuildGraph(project, request), SimpleTaskContext(log = { editLog.appendLine(it) }), 2,
                )
            }
            val detail = "ran=${edited.ranTasks.map { it.value }} skipped=${edited.skippedTasks.map { it.value }}\n$editLog"
            assertTrue(edited.succeeded, "rebuild after the .aidl edit failed:\n$editLog")
            assertTrue(TaskName(":app:compileAidlDebug") in edited.ranTasks, detail)
            assertTrue(TaskName(":app:compileJavaDebug") in edited.ranTasks, detail)
            assertTrue(generated.readText().contains("public static final int VERSION = 2;"), generated.readText())
        }
    }

    /** A module with no `.aidl` registers no task at all, so the graph of an ordinary app is untouched. */
    @Test
    fun aModuleWithoutAidlRegistersNoTask() {
        val sdk = assumeAndroidSdk()
        testEnv("android-no-aidl") { env ->
            val dir = env.dir
            val store = appWithAidl(dir, env.platform, withAidl = false)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val graph = AndroidBuildSystem.subprocess(sdk, signing, bootClasspath = bootClasspathOf(sdk)).createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.COMPILE_ONLY),
            )
            assertFalse(graph.tasks.any { "compileAidl" in it.name.value }, "an aidl-free module gained an AIDL task")
        }
    }

    private fun bootClasspathOf(sdk: AndroidSdk): List<Path> =
        listOf(sdk.androidJar) + listOfNotNull(sdk.coreLambdaStubs.takeIf { Files.exists(it) })

    private fun appWithAidl(dir: Path, platform: PlatformCore, withAidl: Boolean = true): ProjectModelStore {
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

        dir.writeSource("app/src/main/AndroidManifest.xml", MANIFEST)
        dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
        if (withAidl) {
            dir.writeSource("app/src/main/aidl/com/example/app/IGreeter.aidl", AIDL)
            dir.writeSource("app/src/main/java/com/example/app/GreeterService.java", SERVICE)
        }
        return store
    }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"/>
            </manifest>
        """.trimIndent()

        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">Aidl Demo</string></resources>
        """.trimIndent()

        val AIDL = """
            package com.example.app;

            interface IGreeter {
                String greet(String name);
            }
        """.trimIndent()

        /** A real bound service against the generated stub: the shape the whole feature exists to support. */
        val SERVICE = """
            package com.example.app;

            import android.app.Service;
            import android.content.Intent;
            import android.os.IBinder;

            public class GreeterService extends Service {
                private final IGreeter.Stub binder = new IGreeter.Stub() {
                    @Override public String greet(String name) {
                        return "hello " + name;
                    }
                };

                @Override public IBinder onBind(Intent intent) {
                    return binder;
                }
            }
        """.trimIndent()
    }
}
