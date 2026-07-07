package dev.ide.android.support

import dev.ide.android.support.tasks.ApkPackaging
import dev.ide.android.support.tools.AndroidSdk
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
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 */
class AndroidPackagingBuildTest {

    @Test
    fun packagesNativeLibsAndJavaResourcesFaithfully() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-packaging")
        val platform = PlatformCore()
        try {
            // A runtime JAR dependency whose non-class content must reach the APK: a native lib, a service
            // registration, and manifest noise (which the default excludes must strip).
            val depJar = buildDepJar(dir.resolve("depjar-build"), dir.resolve("depjar.jar"), sdk.androidJar)

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("depjar").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(depJar)); commit() }

            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("depjar"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            write(dir, "app/src/main/AndroidManifest.xml", APP_MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", APP_STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)
            // The module's own native lib + Java resource + a service registration.
            writeBytes(dir, "app/src/main/jniLibs/x86/libapp.so", "app-native".toByteArray())
            write(dir, "app/src/main/resources/foo/data.txt", "hello from java resources")
            write(dir, "app/src/main/resources/META-INF/services/com.example.Svc", "com.example.AppImpl")

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
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    /** Compile one class, then repack it with raw native-lib / service / manifest entries into a runtime jar. */
    private fun buildDepJar(workDir: Path, jar: Path, androidJar: Path): Path {
        val srcDir = workDir.resolve("src")
        val classes = workDir.resolve("classes")
        write(srcDir, "com/example/dep/DepUtil.java", DEP_UTIL)
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

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
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
