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
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the native Android pipeline is library-aware. The app depends on a plain JAR and an AAR; the
 * build must put both on the compile classpath and dex them, merge the AAR's resources into the app's
 * `R`, and package the AAR's assets. The app references a JAR class, an AAR class, and an AAR string
 * resource, so a successful compile already proves classpath + AAR `classes.jar` + AAR resource merge all
 * work; the APK assertions then confirm the AAR's assets are packaged.
 */
class AndroidLibraryAwareTest {

    @Test
    fun buildsApkWithJarAndAarDependencies() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-libs")
        val platform = PlatformCore()
        try {
            // Two prebuilt artifacts living under the workspace root.
            val jarLib = compileJar(dir.resolve("jarlib"), "jarlib", "com/example/jarlib/JarGreeter.java", JAR_GREETER, sdk.androidJar)
            val aar = buildAar(dir.resolve("aarlib-build"), dir.resolve("aarlib.aar"), sdk.androidJar)

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }

            store.workspace.libraryTable.create("jarlib").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(jarLib)); commit() }
            store.workspace.libraryTable.create("aarlib").apply { kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(aar)); commit() }

            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("jarlib"), DependencyScope.IMPLEMENTATION))
                    addDependency(LibraryDependency(LibraryRef("aarlib"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            write(dir, "app/src/main/AndroidManifest.xml", APP_MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", APP_STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)
            write(dir, "app/src/main/assets/app_asset.txt", "from app\n")

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
            assertTrue(outcome.succeeded, "library-aware APK build failed:\n$log")

            val apk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            val entries = ZipFile(apk.toFile()).use { it.entries().toList().map { e -> e.name }.toSet() }
            assertTrue("classes.dex" in entries, "no dex: $entries")
            assertTrue("assets/app_asset.txt" in entries, "app asset missing: $entries")
            assertTrue("assets/aar_asset.txt" in entries, "AAR asset not packaged: $entries")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    /** Compile one source into `<workDir>/classes` then pack it into `<workDir>/<name>.jar`. */
    private fun compileJar(workDir: Path, name: String, srcRel: String, src: String, androidJar: Path): Path {
        val srcDir = workDir.resolve("src")
        val classes = workDir.resolve("classes")
        write(srcDir, srcRel, src)
        val r = JdtBatchCompiler.compile(listOf(srcDir.resolve(srcRel)), listOf(androidJar), classes, "17")
        check(r.success) { "library compile failed: ${r.messages}" }
        val jar = workDir.resolve("$name.jar")
        ApkPackaging.jarClasses(classes, jar)
        return jar
    }

    /** Assemble a minimal `.aar`: `classes.jar` + `res/values` + `AndroidManifest.xml` + `assets/`. */
    private fun buildAar(workDir: Path, aar: Path, androidJar: Path): Path {
        val classesJar = compileJar(workDir, "classes", "com/example/aarlib/AarGreeter.java", AAR_GREETER, androidJar)
        Files.createDirectories(aar.parent)
        ZipOutputStream(Files.newOutputStream(aar)).use { zos ->
            fun put(entry: String, bytes: ByteArray) { zos.putNextEntry(ZipEntry(entry)); zos.write(bytes); zos.closeEntry() }
            put("classes.jar", Files.readAllBytes(classesJar))
            put("AndroidManifest.xml", AAR_MANIFEST.trimIndent().toByteArray())
            put("res/values/strings.xml", AAR_STRINGS.trimIndent().toByteArray())
            put("assets/aar_asset.txt", "from aar\n".toByteArray())
        }
        return aar
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val JAR_GREETER = """
            package com.example.jarlib;
            public final class JarGreeter { public static String hello() { return "jar"; } }
        """
        val AAR_GREETER = """
            package com.example.aarlib;
            public final class AarGreeter { public static String hi() { return "aar"; } }
        """
        val AAR_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.aarlib">
                <application/>
            </manifest>
        """
        val AAR_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="aar_label">From AAR</string></resources>
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
            <resources><string name="app_name">Lib Demo</string></resources>
        """
        // References a JAR class, an AAR class, AND an AAR string resource — compiling proves all three paths.
        val APP_ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            import com.example.jarlib.JarGreeter;
            import com.example.aarlib.AarGreeter;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView tv = new TextView(this);
                    tv.setText(JarGreeter.hello() + AarGreeter.hi() + getString(R.string.aar_label));
                    setContentView(tv);
                }
            }
        """
    }
}
