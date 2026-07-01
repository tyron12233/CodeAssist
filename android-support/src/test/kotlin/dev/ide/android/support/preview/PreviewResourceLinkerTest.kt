package dev.ide.android.support.preview

import dev.ide.android.support.AndroidAppModuleType
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.PreviewResourceLinker
import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.tools.Aapt2Subprocess
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
import dev.ide.model.Module
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
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [PreviewResourceLinker] over a real built `android-app`: builds once with the native toolchain
 * (aapt2 + JDT + D8 + apksig) so the build's compiled resources + merged manifest exist, then relinks the
 * live editor buffer over them. Asserts (a) an EDITED layout's live text is linked, (b) a layout added since
 * the build (the "<name> not found in linked resources" case) lands in the linked output, and (c) re-linking
 * the same text is cached. Skipped (not failed) when no Android SDK is installed.
 */
class PreviewResourceLinkerTest {

    @Test
    fun relinksLiveBufferAndNewLayoutOverTheBuild() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("preview-relink")
        val platform = PlatformCore()
        try {
            val store = buildAppWorkspace(dir, platform)
            val project = store.workspace.projects.single()
            val module: Module = project.modules.single { it.name == "app" }
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)

            val buildSystem = AndroidBuildSystem.subprocess(sdk, signing)
            val graph = buildSystem.createBuildGraph(
                project, BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val cache = BuildCache(dir.resolve(".caches/build"))
            val log = StringBuilder()
            val outcome = runBlocking { TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
            assertTrue(outcome.succeeded, "build failed:\n$log")

            val linker = PreviewResourceLinker(
                Aapt2Subprocess(sdk.aapt2), sdk.androidJar, dir.resolve(".caches/preview-res"),
            )
            val layoutDir = dir.resolve("app/src/main/res/layout")
            val resDirs = AndroidResources.resourceDirs(module, store.workspace)
            val manifest = AndroidBuildSystem.mergedManifestPath(module, "debug")

            // (a) An EDITED existing layout: the live buffer (a second TextView) links over the saved copy.
            // The buffer is real file content, so the XML declaration is at the start (trimIndent, as on disk).
            val baseLayout = LAYOUT.trimIndent()
            val editedText = baseLayout.replace("</LinearLayout>", "    <TextView android:id=\"@+id/edited\"\n" +
                "        android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>\n</LinearLayout>")
            val edited = linker.link(module, "debug", resDirs, layoutDir.resolve("activity_main.xml"), editedText, manifest, "com.example.app", 24, 34)
            assertNull(edited.error, "edited relink failed: ${edited.error}")
            val editedAp = assertNotNull(edited.resourcesAp, "edited relink produced no resources.ap_")
            assertTrue(Files.isRegularFile(editedAp), "preview resources.ap_ missing at $editedAp")
            val editedEntries = zipEntries(editedAp)
            assertTrue("resources.arsc" in editedEntries, "preview ap_ lacks resources.arsc: $editedEntries")
            assertTrue(editedEntries.any { it.endsWith("activity_main.xml") }, "preview ap_ lacks the edited layout: $editedEntries")

            // (b) A layout that does NOT exist on disk / in the build (the fragment_surahs case): adding it via
            // the live buffer lands it in the linked output, so getIdentifier would resolve it.
            val newLayout = linker.link(module, "debug", resDirs, layoutDir.resolve("fragment_surahs.xml"), baseLayout, manifest, "com.example.app", 24, 34)
            assertNull(newLayout.error, "new-layout relink failed: ${newLayout.error}")
            val newAp = assertNotNull(newLayout.resourcesAp, "new-layout relink produced no resources.ap_")
            assertTrue(zipEntries(newAp).any { it.endsWith("fragment_surahs.xml") },
                "the newly added layout is not in the relinked output")

            // (c) Re-linking the same text is cached (same output, no error).
            val again = linker.link(module, "debug", resDirs, layoutDir.resolve("fragment_surahs.xml"), baseLayout, manifest, "com.example.app", 24, 34)
            assertEquals(newAp, again.resourcesAp, "cached relink should return the same resources.ap_")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun relinksWithoutAPriorBuild() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("preview-relink-nobuild")
        val platform = PlatformCore()
        try {
            val store = buildAppWorkspace(dir, platform)   // writes res + manifest, but we never build
            val module: Module = store.workspace.projects.single().modules.single { it.name == "app" }

            val linker = PreviewResourceLinker(
                Aapt2Subprocess(sdk.aapt2), sdk.androidJar, dir.resolve(".caches/preview-res"),
            )
            // No build ran → the linker self-builds its base from the live res tree, using the module's own
            // manifest (no merged manifest exists yet).
            val resDirs = AndroidResources.resourceDirs(module, store.workspace)
            val manifest = dir.resolve("app/src/main/AndroidManifest.xml")
            val layout = dir.resolve("app/src/main/res/layout/activity_main.xml")
            val r = linker.link(module, "debug", resDirs, layout, LAYOUT.trimIndent(), manifest, "com.example.app", 24, 34)

            assertNull(r.error, "relink without a build failed: ${r.error}")
            val ap = assertNotNull(r.resourcesAp, "relink without a build produced no resources.ap_")
            val entries = zipEntries(ap)
            assertTrue("resources.arsc" in entries, "ap_ lacks resources.arsc: $entries")
            assertTrue(entries.any { it.endsWith("activity_main.xml") }, "ap_ lacks the layout: $entries")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun generatesPreviewRJarWithPerExtraPackageRClasses() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("preview-rjar")
        val platform = PlatformCore()
        try {
            val store = buildAppWorkspace(dir, platform)   // no build: the linker self-builds its base
            val module: Module = store.workspace.projects.single().modules.single { it.name == "app" }

            val linker = PreviewResourceLinker(
                Aapt2Subprocess(sdk.aapt2), sdk.androidJar, dir.resolve(".caches/preview-res"),
            )
            val resDirs = AndroidResources.resourceDirs(module, store.workspace)
            val manifest = dir.resolve("app/src/main/AndroidManifest.xml")
            val layout = dir.resolve("app/src/main/res/layout/activity_main.xml")

            // A library/framework view references its OWN `R` at inflate time (e.g. CoordinatorLayout →
            // androidx.coordinatorlayout.R$attr). AARs don't ship their R, so the preview must emit one per extra
            // package (as the build does via aapt2 --extra-packages) into the preview R.jar — else inflation
            // crashes with NoClassDefFoundError. Assert both the app's own R and the extra package's R are there.
            val r = linker.link(
                module, "debug", resDirs, layout, LAYOUT.trimIndent(), manifest, "com.example.app", 24, 34,
                extraPackages = listOf("com.example.lib"),
            )
            assertNull(r.error, "relink failed: ${r.error}")
            val rJar = assertNotNull(r.rJar, "no preview R.jar was generated")
            assertTrue(Files.isRegularFile(rJar), "preview R.jar missing at $rJar")
            val classes = zipEntries(rJar)
            assertTrue("com/example/app/R\$string.class" in classes, "app R missing from preview R.jar: $classes")
            assertTrue("com/example/lib/R\$string.class" in classes, "extra-package R missing from preview R.jar: $classes")

            // Re-linking the same buffer + extra packages is cached, and still surfaces the R.jar.
            val again = linker.link(
                module, "debug", resDirs, layout, LAYOUT.trimIndent(), manifest, "com.example.app", 24, 34,
                extraPackages = listOf("com.example.lib"),
            )
            assertEquals(rJar, again.rJar, "cached relink should return the same preview R.jar")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun zipEntries(ap: Path): Set<String> =
        ZipFile(ap.toFile()).use { z -> z.entries().toList().map { it.name }.toSet() }

    private fun buildAppWorkspace(dir: Path, platform: PlatformCore): ProjectModelStore {
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
        write(dir, "app/src/main/AndroidManifest.xml", MANIFEST)
        write(dir, "app/src/main/res/values/strings.xml", STRINGS)
        write(dir, "app/src/main/res/layout/activity_main.xml", LAYOUT)
        write(dir, "app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
        return store
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """

        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">CodeAssist Demo</string>
            </resources>
        """

        val LAYOUT = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:text="@string/app_name"/>
            </LinearLayout>
        """

        val ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                }
            }
        """
    }
}
