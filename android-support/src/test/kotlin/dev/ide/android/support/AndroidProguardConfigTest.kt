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
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end ProGuard/R8 configuration: a minify release build with both an inline keep rule
 * (`proguardRules`) and a file-based one (`proguardFiles` -> `proguard-rules.pro`) must keep exactly the
 * named classes and shrink away an unreferenced one, while emitting `mapping.txt` and the aapt2
 * manifest-derived keep rules. SDK-gated (runs R8 for real through the in-process wiring).
 */
class AndroidProguardConfigTest {

    @Test
    fun keepRulesRetainNamedClassesAndShrinkAwayDeadCode() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-proguard")
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
                                BuildType(
                                    "release", debuggable = false, minifyEnabled = true,
                                    proguardFiles = listOf("proguard-rules.pro"),
                                    proguardRules = listOf("-keep class com.example.app.Kept { *; }"),
                                ),
                            ),
                        ),
                    )
                }
                commit()
            }
            write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
            write(dir, "app/src/main/java/com/example/app/Kept.java", "package com.example.app; public class Kept {}")
            write(dir, "app/src/main/java/com/example/app/KeptByFile.java", "package com.example.app; public class KeptByFile {}")
            write(dir, "app/src/main/java/com/example/app/Dead.java", "package com.example.app; public class Dead {}")
            // File-based keep rule (a proguardFiles entry resolved relative to the module dir).
            write(dir, "app/proguard-rules.pro", "-keep class com.example.app.KeptByFile { *; }")

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing)
            val request = BuildRequest(listOf(ModuleId("app")), VariantSelector("release"), BuildGoal.PACKAGE)
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                    .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "minified build failed:\n$log")

            val apk = dir.resolve("app/build/outputs/apk/release/app-release.apk")
            assertTrue(Files.isRegularFile(apk), "signed release APK missing")

            // mapping.txt is emitted for stack-trace de-obfuscation.
            val mapping = dir.resolve("app/build/outputs/mapping/release/mapping.txt")
            assertTrue(Files.isRegularFile(mapping) && Files.size(mapping) > 0, "mapping.txt must be produced")

            // aapt2 generated the manifest-derived keep rules (which keep MainActivity).
            val aaptRules = dir.resolve("app/build/intermediates/android/release/aapt_rules.txt")
            assertTrue(Files.isRegularFile(aaptRules), "aapt2 manifest keep rules must be generated")
            assertTrue("MainActivity" in Files.readAllBytes(aaptRules).decodeToString(), "manifest activity must be kept by aapt rules")

            // The kept classes survive (name preserved by -keep); the unreferenced, unkept one is shrunk away.
            val dex = apkDexBytes(apk)
            assertTrue(dexContains(dex, "Lcom/example/app/Kept;"), "inline -keep rule must retain Kept")
            assertTrue(dexContains(dex, "Lcom/example/app/KeptByFile;"), "proguardFiles -keep rule must retain KeptByFile")
            assertFalse(dexContains(dex, "Lcom/example/app/Dead;"), "unreferenced, unkept class must be shrunk away")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun apkDexBytes(apk: Path): ByteArray {
        val out = ByteArrayOutputStream()
        ZipFile(apk.toFile()).use { zf ->
            zf.entries().asSequence().filter { it.name.matches(Regex("""classes\d*\.dex""")) }
                .forEach { e -> zf.getInputStream(e).use { it.copyTo(out) } }
        }
        return out.toByteArray()
    }

    /** Dex stores type descriptors as MUTF-8 strings; for an ASCII descriptor that is a plain byte search. */
    private fun dexContains(dex: ByteArray, descriptor: String): Boolean {
        val needle = descriptor.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..dex.size - needle.size) {
            for (j in needle.indices) if (dex[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    TextView tv = new TextView(this);
                    tv.setText("minified");
                    setContentView(tv);
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
            <resources><string name="app_name">Min</string></resources>
        """
    }
}
