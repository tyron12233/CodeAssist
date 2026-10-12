package dev.ide.android.support.preview

import dev.ide.android.support.AndroidAppModuleType
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.PreviewResourceLinker
import dev.ide.android.support.assumeAndroidSdk
import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.tools.Aapt2Subprocess
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
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.ide.model.impl.open

/**
 * Drives [PreviewResourceLinker] over a real built `android-app`: builds once with the native toolchain
 * (aapt2 + JDT + D8 + apksig) so the build's compiled resources exist, then relinks the
 * live editor buffer over them. Asserts (a) an EDITED layout's live text is linked, (b) a layout added since
 * the build (the "<name> not found in linked resources" case) lands in the linked output, and (c) re-linking
 * the same text is cached. Skipped (not failed) when no Android SDK is installed.
 */
class PreviewResourceLinkerTest {

    @Test
    fun relinksLiveBufferAndNewLayoutOverTheBuild() {
        val sdk = assumeAndroidSdk()

        testEnv("preview-relink") { env ->
            val dir = env.dir
            val platform = env.platform
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

            // (a) An EDITED existing layout: the live buffer (a second TextView) links over the saved copy.
            // The buffer is real file content, so the XML declaration is at the start (trimIndent, as on disk).
            val baseLayout = LAYOUT.trimIndent()
            val editedText = baseLayout.replace("</LinearLayout>", "    <TextView android:id=\"@+id/edited\"\n" +
                "        android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>\n</LinearLayout>")
            val edited = linker.link(module, "debug", resDirs, layoutDir.resolve("activity_main.xml"), editedText, "com.example.app", 24, 34)
            assertNull(edited.error, "edited relink failed: ${edited.error}")
            val editedAp = assertNotNull(edited.resourcesAp, "edited relink produced no resources.ap_")
            assertTrue(Files.isRegularFile(editedAp), "preview resources.ap_ missing at $editedAp")
            val editedEntries = zipEntries(editedAp)
            assertTrue("resources.arsc" in editedEntries, "preview ap_ lacks resources.arsc: $editedEntries")
            assertTrue(editedEntries.any { it.endsWith("activity_main.xml") }, "preview ap_ lacks the edited layout: $editedEntries")

            // (b) A layout that does NOT exist on disk / in the build (the fragment_surahs case): adding it via
            // the live buffer lands it in the linked output, so getIdentifier would resolve it.
            val newLayout = linker.link(module, "debug", resDirs, layoutDir.resolve("fragment_surahs.xml"), baseLayout, "com.example.app", 24, 34)
            assertNull(newLayout.error, "new-layout relink failed: ${newLayout.error}")
            val newAp = assertNotNull(newLayout.resourcesAp, "new-layout relink produced no resources.ap_")
            assertTrue(zipEntries(newAp).any { it.endsWith("fragment_surahs.xml") },
                "the newly added layout is not in the relinked output")

            // (c) Re-linking the same text is cached (same output, no error).
            val again = linker.link(module, "debug", resDirs, layoutDir.resolve("fragment_surahs.xml"), baseLayout, "com.example.app", 24, 34)
            assertEquals(newAp, again.resourcesAp, "cached relink should return the same resources.ap_")
        }
    }

    @Test
    fun relinksWithoutAPriorBuild() {
        val sdk = assumeAndroidSdk()

        testEnv("preview-relink-nobuild") { env ->
            val dir = env.dir
            val platform = env.platform
            val store = buildAppWorkspace(dir, platform)   // writes res + manifest, but we never build
            val module: Module = store.workspace.projects.single().modules.single { it.name == "app" }

            val linker = PreviewResourceLinker(
                Aapt2Subprocess(sdk.aapt2), sdk.androidJar, dir.resolve(".caches/preview-res"),
            )
            // No build ran → the linker self-builds its base from the live res tree. The source manifest is the
            // shape a freshly imported project has before its first build: no `package` (the namespace lives in
            // the build script), a placeholder and a resource the project lacks. aapt2 rejects all three, so the
            // preview must not link against it.
            dir.writeSource("app/src/main/AndroidManifest.xml", NAMESPACE_ONLY_MANIFEST)
            val resDirs = AndroidResources.resourceDirs(module, store.workspace)
            val layout = dir.resolve("app/src/main/res/layout/activity_main.xml")
            val r = linker.link(module, "debug", resDirs, layout, LAYOUT.trimIndent(), "com.example.app", 24, 34)

            assertNull(r.error, "relink without a build failed: ${r.error}")
            val ap = assertNotNull(r.resourcesAp, "relink without a build produced no resources.ap_")
            val entries = zipEntries(ap)
            assertTrue("resources.arsc" in entries, "ap_ lacks resources.arsc: $entries")
            assertTrue(entries.any { it.endsWith("activity_main.xml") }, "ap_ lacks the layout: $entries")
        }
    }

    @Test
    fun generatesPreviewRJarWithPerExtraPackageRClasses() {
        val sdk = assumeAndroidSdk()

        testEnv("preview-rjar") { env ->
            val dir = env.dir
            val platform = env.platform
            val store = buildAppWorkspace(dir, platform)   // no build: the linker self-builds its base
            val module: Module = store.workspace.projects.single().modules.single { it.name == "app" }

            val linker = PreviewResourceLinker(
                Aapt2Subprocess(sdk.aapt2), sdk.androidJar, dir.resolve(".caches/preview-res"),
            )
            val resDirs = AndroidResources.resourceDirs(module, store.workspace)
            val layout = dir.resolve("app/src/main/res/layout/activity_main.xml")

            // A library/framework view references its OWN `R` at inflate time (e.g. CoordinatorLayout →
            // androidx.coordinatorlayout.R$attr). AARs don't ship their R, so the preview must emit one per extra
            // package (as the build does via aapt2 --extra-packages) into the preview R.jar — else inflation
            // crashes with NoClassDefFoundError. Assert both the app's own R and the extra package's R are there.
            val r = linker.link(
                module, "debug", resDirs, layout, LAYOUT.trimIndent(), "com.example.app", 24, 34,
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
                module, "debug", resDirs, layout, LAYOUT.trimIndent(), "com.example.app", 24, 34,
                extraPackages = listOf("com.example.lib"),
            )
            assertEquals(rJar, again.rJar, "cached relink should return the same preview R.jar")
        }
    }

    @Test
    fun reportsResourceErrorsInTheUsersOwnFiles() {
        val sdk = assumeAndroidSdk()

        testEnv("preview-relink-errors") { env ->
            val dir = env.dir
            val store = buildAppWorkspace(dir, env.platform)
            val module: Module = store.workspace.projects.single().modules.single { it.name == "app" }
            val linker = PreviewResourceLinker(
                Aapt2Subprocess(sdk.aapt2), sdk.androidJar, dir.resolve(".caches/preview-res"),
            )
            val resDirs = AndroidResources.resourceDirs(module, store.workspace)
            val strings = dir.resolve("app/src/main/res/values/strings.xml")
            val layout = dir.resolve("app/src/main/res/layout/activity_main.xml")
            fun link(text: String = LAYOUT.trimIndent()) =
                linker.link(module, "debug", resDirs, layout, text, "com.example.app", 24, 34)

            // A values entry referencing a missing resource fails the link inside the preview's merged
            // values.xml; the error must name the user's strings.xml and the line declaring the entry.
            dir.writeSource("app/src/main/res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">CodeAssist Demo</string>
                    <color name="accent">@color/missing</color>
                </resources>
            """.trimIndent())
            val dangling = link()
            assertNull(dangling.resourcesAp, "a broken resource set must not produce a render")
            val missing = dangling.resourceErrors.single { "color/missing" in it.message }
            assertEquals(strings, missing.file)
            assertEquals(4, missing.line)

            // A file that doesn't parse fails the base compile; the error names the user's file.
            dir.writeSource("app/src/main/res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">CodeAssist Demo</string
                </resources>
            """.trimIndent())
            val malformed = link()
            assertNull(malformed.resourcesAp)
            assertTrue(malformed.resourceErrors.isNotEmpty(), "no resource errors for a malformed file: ${malformed.error}")
            assertTrue(malformed.resourceErrors.all { it.file == strings }, "errors not mapped to strings.xml: ${malformed.resourceErrors}")

            // Fixed on disk: the next link renders again (the remembered errors were keyed on the old tree).
            dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
            val fixed = link()
            assertNull(fixed.error, "relink after the fix failed: ${fixed.error}")
            assertNotNull(fixed.resourcesAp)

            // The live buffer referencing a missing string: the error names the edited layout, at the line its
            // element starts (aapt2 reports the element, not the attribute).
            val badRef = LAYOUT.trimIndent().replace("@string/app_name", "@string/nope")
            val unresolved = link(badRef).resourceErrors.single { "string/nope" in it.message }
            assertEquals(layout, unresolved.file)
            assertEquals(badRef.lines().indexOfFirst { "<TextView" in it } + 1, unresolved.line)

            // A half-typed buffer that isn't well-formed XML: the error names the edited layout.
            val unclosed = link(LAYOUT.trimIndent().substringBefore("</LinearLayout>")).resourceErrors
            assertTrue(unclosed.isNotEmpty() && unclosed.all { it.file == layout }, "unexpected errors: $unclosed")
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
        dir.writeSource("app/src/main/AndroidManifest.xml", MANIFEST)
        dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
        dir.writeSource("app/src/main/res/layout/activity_main.xml", LAYOUT)
        dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
        return store
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

        val NAMESPACE_ONLY_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="@string/app_name" android:icon="${'$'}{appIcon}"
                    android:roundIcon="@mipmap/ic_launcher_round">
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
