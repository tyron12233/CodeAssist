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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof of the AGP-faithful packaging step: an `android-app` with its own native library
 * (`src/main/jniLibs/x86/libapp.so`) and Java resource (`src/main/resources/foo/data.txt`), plus a JAR
 * dependency carrying a native library (`lib/arm64-v8a/libjar.so`), a `META-INF/services` registration,
 * and a `META-INF/MANIFEST.MF`. The signed APK must contain both `.so` files under `lib/`, the Java
 * resource at the root, and the merged services file — while the per-jar `MANIFEST.MF` is dropped by the
 * default excludes.
 *
 * It also covers the OTHER shape a native library arrives in: a per-ABI classifier artifact
 * (`gdx-platform:1.14.2:natives-arm64-v8a`) holding one bare `libgdx.so` at the archive root. That one is
 * packaged from whichever configuration it was declared in, since a classpath does nothing with it and the
 * failure is invisible until the app calls `System.loadLibrary`.
 */
class AndroidPackagingBuildTest {

    @Test
    fun packagesNativeLibsAndJavaResourcesFaithfully() {
        val sdk = assumeAndroidSdk()

        testEnv("android-packaging") { env ->
            val dir = env.dir
            val platform = env.platform
            // A runtime JAR dependency whose non-class content must reach the APK: a native lib, a service
            // registration, and manifest noise (which the default excludes must strip).
            val depJar = buildDepJar(dir.resolve("depjar-build"), dir.resolve("depjar.jar"), sdk.androidJar)
            // Per-ABI native artifacts: a bare `.so` at the archive root, the ABI only in the file name's
            // classifier. One declared on a CLASSPATH (what the add flow's default configuration produces,
            // and what a libGDX-style Gradle build imports as) and one in the scope meant for it.
            val gdxArm = nativesJar(dir.resolve("gdx-platform-1.14.2-natives-arm64-v8a.jar"), "arm-native")
            val gdxX86 = nativesJar(dir.resolve("gdx-platform-1.14.2-natives-x86_64.jar"), "x86-native")

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("depjar").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(depJar)); commit() }
            store.workspace.libraryTable.create("gdx-arm").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(gdxArm)); commit() }
            store.workspace.libraryTable.create("gdx-x86").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(gdxX86)); commit() }

            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("depjar"), DependencyScope.IMPLEMENTATION))
                    addDependency(LibraryDependency(LibraryRef("gdx-arm"), DependencyScope.IMPLEMENTATION))
                    addDependency(LibraryDependency(LibraryRef("gdx-x86"), DependencyScope.NATIVES))
                }
                commit()
            }

            dir.writeSource("app/src/main/AndroidManifest.xml", APP_MANIFEST)
            dir.writeSource("app/src/main/res/values/strings.xml", APP_STRINGS)
            dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)
            // The module's own native lib + Java resource + a service registration.
            writeBytes(dir, "app/src/main/jniLibs/x86/libapp.so", "app-native".toByteArray())
            dir.writeSource("app/src/main/resources/foo/data.txt", "hello from java resources")
            dir.writeSource("app/src/main/resources/META-INF/services/com.example.Svc", "com.example.AppImpl")

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing)
            val graph = buildSystem.createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "packaging APK build failed:\n$log")

            val apk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            val entries = readEntries(apk)

            // Native libraries: the app's own and the JAR's, each under lib/<abi>/.
            assertTrue("lib/x86/libapp.so" in entries.keys, "app native lib missing: ${entries.keys}")
            assertTrue("lib/arm64-v8a/libjar.so" in entries.keys, "jar native lib missing: ${entries.keys}")
            assertEquals("app-native", entries["lib/x86/libapp.so"])

            // The classifier artifacts, unpacked into the ABI their file name names. The `implementation`
            // one is the reported crash: it resolved, it built, and the APK carried no `libgdx.so` at all.
            assertEquals("arm-native", entries["lib/arm64-v8a/libgdx.so"], "classpath-scoped natives jar not packaged: ${entries.keys}")
            assertEquals("x86-native", entries["lib/x86_64/libgdx.so"], "natives-scoped jar not packaged: ${entries.keys}")
            // The unpack marker is bookkeeping, not a library: it must not ship, and two ABIs of the same
            // artifact must not collide on it.
            assertTrue(entries.keys.none { it.startsWith("lib/.") }, "unpack bookkeeping shipped: ${entries.keys}")
            assertFalse("Duplicate native library" in log.toString(), "bogus duplicate report:\n$log")

            // Java resources: the module's file at the APK root.
            assertTrue("foo/data.txt" in entries.keys, "java resource missing: ${entries.keys}")
            assertEquals("hello from java resources", entries["foo/data.txt"])

            // Services concatenated across the app + the jar.
            val svc = entries["META-INF/services/com.example.Svc"]?.trim()?.lines()?.toSet()
            assertEquals(setOf("com.example.AppImpl", "com.example.JarImpl"), svc, "services not merged: $svc")

            assertTrue("classes.dex" in entries.keys, "no dex: ${entries.keys}")

            // Default excludes must drop the per-jar MANIFEST.MF. Check the merge task's own output rather than
            // the signed APK — apksig's v1 (JAR) signing re-adds a META-INF/MANIFEST.MF of its own.
            val mergedJavaRes = dir.resolve("app/build/intermediates/android/debug/merged_java_res/merged-java-res.jar")
            assertTrue(Files.isRegularFile(mergedJavaRes), "merged java-res jar not produced")
            val mergedNames = ZipFile(mergedJavaRes.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }
            assertFalse("META-INF/MANIFEST.MF" in mergedNames, "MANIFEST.MF should be excluded by default: $mergedNames")
            assertTrue("foo/data.txt" in mergedNames && "META-INF/services/com.example.Svc" in mergedNames)
        }
    }

    /** Compile one class, then repack it with raw native-lib / service / manifest entries into a runtime jar. */
    private fun buildDepJar(workDir: Path, jar: Path, androidJar: Path): Path {
        val srcDir = workDir.resolve("src")
        val classes = workDir.resolve("classes")
        srcDir.writeSource("com/example/dep/DepUtil.java", DEP_UTIL)
        val r = JdtBatchCompiler.compile(listOf(srcDir.resolve("com/example/dep/DepUtil.java")), listOf(androidJar), classes, "17")
        check(r.success) { "dep compile failed: ${r.messages}" }
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun put(entry: String, bytes: ByteArray) { zos.putNextEntry(ZipEntry(entry)); zos.write(bytes); zos.closeEntry() }
            // Compiled class(es).
            Files.walk(classes).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().forEach {
                    put(classes.relativize(it).toString().replace('\\', '/'), Files.readAllBytes(it))
                }
            }
            put("lib/arm64-v8a/libjar.so", "jar-native".toByteArray())
            put("META-INF/services/com.example.Svc", "com.example.JarImpl".toByteArray())
            put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".toByteArray())
        }
        return jar
    }

    /** A per-ABI natives artifact: one `.so` at the archive ROOT, so only the file name says which ABI. */
    private fun nativesJar(jar: Path, content: String): Path {
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("libgdx.so")); zos.write(content.toByteArray()); zos.closeEntry()
        }
        return jar
    }

    private fun writeBytes(root: Path, rel: String, bytes: ByteArray) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.write(f, bytes)
    }

    private fun readEntries(apk: Path): Map<String, String> = ZipFile(apk.toFile()).use { zf ->
        zf.entries().asSequence().filter { !it.isDirectory }
            .associate { it.name to zf.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        val DEP_UTIL = """
            package com.example.dep;
            public final class DepUtil { public static String tag() { return "dep"; } }
        """
        val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """
        val APP_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">Packaging Demo</string></resources>
        """
        val APP_ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView tv = new TextView(this);
                    tv.setText(getString(R.string.app_name));
                    setContentView(tv);
                }
            }
        """
    }
}
