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
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleDependency
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
 * Proves resource merging across modules: an `android-app` depends on an `android-lib` module (`liba`)
 * that contributes a string resource and whose own code references its own `R`. The build must merge
 * `liba`'s resources into the app, generate an `R` for `liba`'s package (aapt2 `--extra-packages`) so
 * `liba` compiles, and let the app reference the merged resource via its own `R`. Asserts the merged
 * resource is in `resources.arsc` and `liba`'s code is dexed.
 */
class AndroidLibResourceMergeTest {

    @Test
    fun mergesAndroidLibDependencyResources() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-libres")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())
            val appType = types.resolve("android-app")
            val libType = types.resolve("android-lib")

            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("liba", libType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.liba", compileSdk = 34, minSdk = 24, isApplication = false))
                }
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(ModuleDependency(ModuleId("liba"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            write(dir, "liba/src/main/res/values/strings.xml", LIB_STRINGS)
            write(dir, "liba/src/main/java/com/example/liba/LibGreeter.java", LIB_GREETER)
            write(dir, "app/src/main/AndroidManifest.xml", APP_MANIFEST)
            write(dir, "app/src/main/res/values/strings.xml", APP_STRINGS)
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)

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
            assertTrue(outcome.succeeded, "android-lib resource-merge build failed:\n$log")

            val apk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            ZipFile(apk.toFile()).use { zf ->
                val arsc = zf.getInputStream(zf.getEntry("resources.arsc")).readBytes().toString(Charsets.ISO_8859_1)
                assertTrue("liba_greeting" in arsc, "the android-lib's resource was not merged into resources.arsc")
                val dex = zf.getInputStream(zf.getEntry("classes.dex")).readBytes().toString(Charsets.ISO_8859_1)
                assertTrue("Lcom/example/liba/LibGreeter;" in dex, "android-lib code not dexed")
                assertTrue("Lcom/example/liba/R" in dex, "the library's final R must be dexed (by the app)")
            }

            // Decoupled R model: the library's own compiled output carries LibGreeter but NOT R; its R is a
            // separate compile-only stub. The final R is generated/dexed by the app (asserted above).
            assertTrue(Files.exists(dir.resolve("liba/build/classes/com/example/liba/LibGreeter.class")), "lib class missing")
            assertTrue(!Files.exists(dir.resolve("liba/build/classes/com/example/liba/R.class")),
                "the library's dexed output must NOT contain R (it is compile-only)")
            assertTrue(Files.exists(dir.resolve("liba/build/intermediates/r/classes/com/example/liba/R.class")),
                "the compile-only library R should be generated separately")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val LIB_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="liba_greeting">Hello from liba</string></resources>
        """
        // References its OWN R — only compiles if aapt2 --extra-packages generated com.example.liba.R.
        val LIB_GREETER = """
            package com.example.liba;
            public final class LibGreeter { public static int greetingRes() { return R.string.liba_greeting; } }
        """
        val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"><activity android:name=".MainActivity" android:exported="true"/></application>
            </manifest>
        """
        val APP_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">LibRes Demo</string></resources>
        """
        // Uses the merged lib resource through the APP's own R, and the lib's code.
        val APP_ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            import com.example.liba.LibGreeter;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView tv = new TextView(this);
                    tv.setText(getString(R.string.liba_greeting) + LibGreeter.greetingRes());
                    setContentView(tv);
                }
            }
        """
    }
}
