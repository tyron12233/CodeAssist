package dev.ide.android.support

import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
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
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ahead-of-build library dex warm ([AndroidBuildSystem.warmLibraryDexCache]): dexing a project's external
 * libraries into the shared cache after a dependency resolution, so the first build finds them done instead of
 * paying for them while the user waits.
 *
 * The property that makes it worth anything is KEY COMPATIBILITY: the warm has to bank its buckets under exactly
 * the key a build looks them up by, or it is pure wasted CPU. So the test warms, then runs a real build graph
 * through the same dexer and asserts the library is not dexed a second time.
 */
class LibraryDexWarmTest {

    /** Records the file name of every jar handed to [dexArchive], and writes a realistic complete archive. */
    private class RecordingDexer : Dexer {
        val archived = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1))
            return ToolResult.ok(emptyList())
        }

        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir)
            for (input in inputs.filter { Files.exists(it) }) {
                archived.add(input.fileName.toString())
                ZipFile(input.toFile()).use { zf ->
                    zf.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                        val dex = outDir.resolve(e.name.removeSuffix(".class") + ".dex")
                        dex.parent?.let { Files.createDirectories(it) }
                        Files.write(dex, byteArrayOf(1))
                    }
                }
            }
            return ToolResult.ok(emptyList())
        }
    }

    @Test
    fun warmsLibrariesAheadOfTheBuildAndTheBuildReusesThem() {
        val sdk = assumeAndroidSdk()

        testEnv("dex-warm") { env ->
            val dir = env.dir
            val platform = env.platform
            val jarLib = libraryJar(dir.resolve("jarlib.jar"), "com/example/jarlib/Greeter", "com/example/jarlib/Helper")

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("jarlib").apply {
                kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(jarLib)); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    // minSdk 26: no desugaring, so a library's cache key is its own content alone — the shape the
                    // warm shares unconditionally with any build (see warmLibraryDexCache).
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 26, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("jarlib"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", MANIFEST)
            dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
            dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val sharedCache = dir.resolve(".caches/dex")
            val warmRoot = dir.resolve(".caches/dex-warm/app")
            val dexer = RecordingDexer()
            // Both dex ports are the fake: the archives it writes are not real dex, so a real D8 merge would
            // (correctly) reject them.
            val buildSystem =
                AndroidBuildSystem.inProcess(sdk, signing, dexCacheRoot = sharedCache, dexer = dexer, mergeDexer = dexer)
            val app = store.workspace.projects.single().modules.single { it.name == "app" }

            // Cold: the one library is not in the cache, so the warm dexes it.
            val warmed = runBlocking { buildSystem.warmLibraryDexCache(app, "debug", warmRoot) }
            assertEquals(1, warmed, "the cold warm should have dexed the one library")
            assertEquals(listOf("jarlib.jar"), dexer.archived.toList(), "the warm dexed the wrong inputs")
            assertTrue(hasDexUnder(sharedCache), "the warm did not populate the shared cache: ${sharedCache}")

            // Warm again: nothing left to do, and nothing re-dexed.
            dexer.archived.clear()
            assertEquals(0, runBlocking { buildSystem.warmLibraryDexCache(app, "debug", warmRoot) })
            assertTrue(dexer.archived.isEmpty(), "a warm cache must not re-dex: ${dexer.archived}")

            // The real build must find the warm's bucket under its own key and not dex the library again. This is
            // what makes the warm worth doing at all — a mismatched key would silently make it wasted work.
            dexer.archived.clear()
            val graph = buildSystem.createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.DEX),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "dex-goal build failed:\n$log")
            assertTrue(
                dexer.archived.none { it == "jarlib.jar" },
                "the build re-dexed a library the warm had already banked (archived: ${dexer.archived}):\n$log",
            )
            // Sanity: the build did dex SOMETHING (its own classes), so the assertion above isn't vacuous.
            assertTrue(dexer.archived.isNotEmpty(), "the build archived nothing at all:\n$log")
        }
    }

    /** A jar of minimal but valid classes — enough for content hashing, class listing, and a fake per-class dex. */
    private fun libraryJar(jar: Path, vararg internalNames: String): Path {
        jar.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(jar)).use { z ->
            for (name in internalNames) {
                z.putNextEntry(ZipEntry("$name.class"))
                val cw = org.objectweb.asm.ClassWriter(0)
                cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
                cw.visitEnd()
                z.write(cw.toByteArray())
                z.closeEntry()
            }
        }
        return jar
    }

    private fun hasDexUnder(root: Path): Boolean = Files.isDirectory(root) &&
        Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """.trimIndent()

        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">Warm</string></resources>
        """.trimIndent()

        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) { super.onCreate(b); }
            }
        """.trimIndent()
    }
}
