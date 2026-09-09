package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.preview.PreviewRequest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Throughput profile for the interpret-mode real-view render: creates the `android-material-you` project
 * (CoordinatorLayout + FloatingActionButton), resolves deps, then renders the layout several times, timing
 * each. Render 1 is cold (VM class decode + peer dex generation); later renders reuse the VM's class + peer
 * caches. The VmPerf lines are the end-to-end wall-clock; the `ide.preview` render summary (from the `:preview`
 * process) breaks each render into classloader/context/inflate/draw phases + interpreted step count. Not an
 * assertion — a measurement.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.RealViewInterpretPerfSpike
 *     adb logcat -d -s VmPerf ide.preview
 */
@RunWith(AndroidJUnit4::class)
class RealViewInterpretPerfSpike {

    private fun log(m: String) { Log.i("VmPerf", m); println(m) }

    @Test
    fun profileInterpretRender() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = AndroidIde.createProjectManager(ctx)
        val services = pm.create("android-material-you", emptyMap())
        val backend = IdeServicesBackend(initial = services)
        try {
            backend.deps.startPendingDependencyResolution()
            val deadline = System.currentTimeMillis() + 180_000
            Thread.sleep(2_000)
            while (backend.deps.depsState.value.resolving && System.currentTimeMillis() < deadline) Thread.sleep(1_000)

            val layout = File(services.store.rootPath.toFile(), "app/src/main/res/layout/activity_main.xml")
            val text = layout.readText()
            val req = PreviewRequest(widthPx = 1080, heightPx = 2140, density = 2.75f, realViews = true)

            // A handful of renders: #1 cold, the rest warm. Wall-clock includes the host-side relink + the
            // (possibly cross-process) render; the PERF logcat line isolates the in-runtime phases.
            repeat(6) { i ->
                val t0 = System.nanoTime()
                val r = services.layoutPreview(layout.toPath(), text, req)
                val ms = (System.nanoTime() - t0) / 1_000_000
                log("render #${i + 1} wall=${ms}ms rendered=${r?.renderedNativeImage != null} problems=${r?.problems?.size}")
            }
        } finally {
            runCatching { services.close() }
        }
    }
}
