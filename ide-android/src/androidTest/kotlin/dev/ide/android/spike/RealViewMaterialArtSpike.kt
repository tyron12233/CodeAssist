package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidLibraries
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidVariants
import dev.ide.core.IdeServicesBackend
import dev.ide.model.Module
import dev.ide.preview.PreviewRequest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end: create a fresh `android-material-you` project (CoordinatorLayout + FloatingActionButton +
 * material:1.12.0), resolve its dependencies, and render the layout with the interpret-mode real-view path —
 * asserting it produces a bitmap with no owned-rendering fallback. Exercises interpreting real material/androidx
 * view classes (their R styleables, peer construction with constructor-time virtual dispatch, and super calls
 * into real supertypes), the class of view that motivated the interpret path.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.RealViewMaterialArtSpike
 *     adb logcat -d -s VmMatRepro
 */
@RunWith(AndroidJUnit4::class)
class RealViewMaterialArtSpike {

    private fun log(m: String) { Log.i("VmMatRepro", m); println(m) }

    @Test
    fun materialYouProjectRealViewPreview() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = AndroidIde.createProjectManager(ctx)
        val services = pm.create("android-material-you", emptyMap())
        val backend = IdeServicesBackend(initial = services)
        try {
            // Resolve the template's declared deps (material + transitives) and wait for the background resolve.
            backend.deps.startPendingDependencyResolution()
            val deadline = System.currentTimeMillis() + 180_000
            // Give the resolve a beat to flip `resolving` on, then wait for it to settle.
            Thread.sleep(2_000)
            while (backend.deps.depsState.value.resolving && System.currentTimeMillis() < deadline) {
                Thread.sleep(1_000)
            }
            log("deps settled; unresolved=${backend.deps.depsState.value.unresolved}")

            val module: Module = services.modules().first { it.facets.get(AndroidFacet.KEY) != null }
            val variant = AndroidVariants.defaultVariant(module)
            val variantName = variant?.name ?: "debug"
            val explodeRoot = AndroidBuildSystem.explodedAarPath(module, variantName)
            val resolved = runCatching {
                AndroidLibraries.resolve(module, explodeRoot, variant?.configurations)
            }
            resolved.fold(
                onSuccess = { r ->
                    log("AndroidLibraries.resolve OK: dexJars=${r.dexJars.size} compileJars=${r.compileJars.size} aarPackages=${r.aarPackages.size}")
                    log("  coordinatorlayout in dexJars = ${r.dexJars.any { it.toString().contains("coordinatorlayout") }}")
                    log("  sample dexJars = ${r.dexJars.take(5).map { it.fileName }}")
                },
                onFailure = { log("AndroidLibraries.resolve THREW ${it.javaClass.name}: ${it.message}") },
            )

            val layout = File(services.store.rootPath.toFile(), "app/src/main/res/layout/activity_main.xml")
            log("activity_main exists=${layout.exists()} at $layout")
            assertTrue("activity_main.xml should exist in the material-you template", layout.exists())
            val result = services.layoutPreview(
                layout.toPath(),
                layout.readText(),
                PreviewRequest(widthPx = 1080, heightPx = 2140, density = 2.75f, realViews = true),
            )
            log(
                "layoutPreview -> nativeImage=${result?.renderedNativeImage != null} " +
                    "png=${result?.renderedImage != null} buildRequired=${result?.buildRequired} " +
                    "problems=${result?.problems?.map { it.message }}"
            )
            // The interpreted real-view path must render the CoordinatorLayout + FloatingActionButton + TextView
            // material layout to a bitmap with no fallback problems.
            assertNotNull("layoutPreview returned no result", result)
            assertTrue(
                "real-view render fell back to owned rendering: ${result?.problems?.map { it.message }}",
                result?.renderedNativeImage != null && result.problems.isEmpty(),
            )
        } finally {
            runCatching { services.close() }
        }
    }
}
