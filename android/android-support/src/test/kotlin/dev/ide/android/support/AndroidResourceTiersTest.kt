package dev.ide.android.support

import dev.ide.android.support.tasks.ApkPackaging
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import dev.ide.model.impl.open
import dev.ide.vfs.local.fileFor
import dev.ide.build.engine.TaskStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.test.assertEquals

/**
 * A library's resources are merged and compiled apart from the project's, so that editing one of the app's does
 * not redo the libraries'. Two things must hold: what overrides what is the same as when they were one tree
 * (the app's resource replaces a library's of the same name), and an app edit leaves the library tasks alone.
 */
class AndroidResourceTiersTest {

    @Test
    fun theAppsResourceOverridesTheLibrarysAndAnEditLeavesTheLibraryTasksUpToDate() {
        val sdk = assumeAndroidSdk()
        testEnv("android-res-tiers") { env ->
            val dir = env.dir
            val platform = env.platform
            val exploded = dir.resolve("exploded/widgets-1.0-exploded")
            dir.resolve("exploded/widgets-1.0-exploded").writeSource("res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="shared">from the library</string>
                    <string name="lib_only">only in the library</string>
                </resources>
            """)
            exploded.writeSource("AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.example.widgets"><application/></manifest>
            """)
            // Its symbol table, as a real AAR ships one: the library's R holds these and nothing of the app's.
            Files.writeString(exploded.resolve("R.txt"), "int string lib_only 0x0\nint string shared 0x0\n")
            // A code-free AAR's classes.jar: a manifest and nothing else.
            val classesJar = exploded.resolve("classes.jar")
            java.util.jar.JarOutputStream(Files.newOutputStream(classesJar), java.util.jar.Manifest().apply {
                mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
            }).close()

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("widgets").apply {
                kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(classesJar)); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("widgets"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                    <application android:label="@string/shared"><activity android:name=".MainActivity" android:exported="true"/></application>
                </manifest>
            """)
            fun appStrings(own: String, extra: String = "") = dir.writeSource("app/src/main/res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="shared">from the app</string>
                    <string name="app_only">$own</string>
                    $extra
                </resources>
            """)
            appStrings("first value")
            dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", """
                package com.example.app;
                public class MainActivity extends android.app.Activity {
                    int a = R.string.app_only, b = R.string.lib_only;
                }
            """)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE)
            val cache = BuildCache(dir.resolve(".caches/build"))
            val log = StringBuilder()
            fun build(): Map<String, TaskStatus> {
                val events = ConcurrentHashMap<String, TaskStatus>()
                log.setLength(0)
                val outcome = runBlocking {
                    TaskExecutorImpl(cache, onEvent = { n, s -> events[n.value] = s })
                        .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
                }
                assertTrue(outcome.succeeded, "the build failed:\n$log")
                return events
            }
            fun table(): String {
                val ap = dir.resolve("app/build/intermediates/android/debug/resources.ap_")
                return ZipFile(ap.toFile()).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes().toString(Charsets.ISO_8859_1) }
            }

            val first = build()
            assertEquals(TaskStatus.Succeeded, first[":app:mergeLibraryResourcesDebug"], "the libraries' resources are a tier of their own: $first")
            assertEquals(TaskStatus.Succeeded, first[":app:compileLibraryResourcesDebug"])
            table().let { arsc ->
                assertTrue("from the app" in arsc, "the app's value of a resource both define")
                assertTrue("from the library" !in arsc, "the library's value of it must be replaced, not kept beside the app's")
                assertTrue("only in the library" in arsc && "first value" in arsc, "what only one of them defines is there")
            }

            appStrings("second value")
            val second = build()
            assertEquals(TaskStatus.UpToDate, second[":app:mergeLibraryResourcesDebug"], "an app edit does not re-merge the libraries: $second")
            assertEquals(TaskStatus.UpToDate, second[":app:compileLibraryResourcesDebug"], "nor recompile them")
            assertEquals(TaskStatus.Succeeded, second[":app:aapt2CompileDebug"], "the project's own resources are recompiled")
            table().let { arsc ->
                assertTrue("second value" in arsc && "first value" !in arsc, "the edit is in the table")
                assertTrue("from the app" in arsc && "from the library" !in arsc && "only in the library" in arsc, "and the rest is as it was")
            }

            // A NEW resource, named to sort before every other string: numbered afresh it would take the first id
            // and push the rest along. The ids are carried over instead, so nothing that existed is renumbered,
            // and the libraries' R, which has no new field, is byte for byte the jar it was.
            val inter = dir.resolve("app/build/intermediates/android/debug")
            fun ids() = Files.readAllLines(inter.resolve("compile_and_runtime_not_namespaced_r_class_jar/R.txt"))
                .mapNotNull { Regex("""int string (\w+) (0x[0-9a-fA-F]+)""").find(it)?.destructured?.let { (n, v) -> n to v } }.toMap()
            val libraryR = inter.resolve("compile_and_runtime_not_namespaced_r_class_jar/R-libraries.jar")
            val idsBefore = ids()
            val libraryRBefore = Files.readAllBytes(libraryR)
            assertTrue(Files.isRegularFile(inter.resolve("resource-ids.txt")), "the link keeps the ids it assigned")

            appStrings("second value", extra = """<string name="aaa_new">new</string>""")
            val third = build()
            val idsAfter = ids()
            assertTrue("aaa_new" in idsAfter && "aaa_new" !in idsBefore, "the new string has an id: $idsAfter")
            assertEquals(idsBefore, idsAfter - "aaa_new", "no existing string is renumbered")
            assertTrue(Files.readAllBytes(libraryR).contentEquals(libraryRBefore), "the libraries' R jar is unchanged")
            assertEquals(TaskStatus.Succeeded, third[":app:generateRFileDebug"], "R was regenerated for the new name")
            ZipFile(inter.resolve("compile_and_runtime_not_namespaced_r_class_jar/R-app.jar").toFile()).use { z ->
                val strings = z.getInputStream(z.getEntry("com/example/app/R\$string.class")).readBytes().toString(Charsets.ISO_8859_1)
                assertTrue("aaa_new" in strings, "and the app's R has it")
                assertTrue(z.getEntry("io/example/widgets/R\$string.class") == null, "the app's R jar holds no library's R")
            }
        }
    }
}
