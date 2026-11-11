package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.model.impl.rootPath
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

            // #1 is cold. #2-#3 repeat the same text (every cache hits). #4 on each render an EDITED buffer (a
            // changed attribute value, as a keystroke would), so the relink runs: that is the per-edit cost.
            // Wall-clock includes the host-side relink + the (possibly cross-process) render; the `ide.preview`
            // logcat lines isolate the phases on each side.
            repeat(12) { i ->
                val edited = if (i < 3) text else text.replaceFirst("android:layout_width=\"match_parent\"", "android:layout_width=\"match_parent\" android:alpha=\"0.${90 + i}\"")
                val t0 = System.nanoTime()
                val r = services.layoutPreview(layout.toPath(), edited, req)
                val ms = (System.nanoTime() - t0) / 1_000_000
                log("render #${i + 1} ${if (i == 0) "cold" else if (i < 3) "same" else "edit"} wall=${ms}ms rendered=${r?.renderedNativeImage != null} problems=${r?.problems?.size}")
            }
        } finally {
            runCatching { services.close() }
        }
    }
}
