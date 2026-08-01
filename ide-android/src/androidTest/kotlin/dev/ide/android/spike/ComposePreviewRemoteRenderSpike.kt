package dev.ide.android.spike

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.ComposePreviewRemoteClient
import dev.ide.core.LoweredComposePreview
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-preview isolation **Phase 1b**: render a real `@Preview` OUT-OF-PROCESS, end-to-end. It drives
 * [ComposePreviewRemoteClient] — which serializes the lowered preview ([dev.ide.core.preview.ComposePreviewWireCodec]),
 * hands it to [dev.ide.android.preview.ComposePreviewSessionService] in the `:preview` OS process, and maps the
 * returned frame back to a `Bitmap` — and asserts the frame (1) came from a DIFFERENT pid than the test process
 * (isolation holds) and (2) actually drew (non-uniform pixels). This is the whole IDE↔:preview pipeline the
 * material3-flip preview will run over, minus the live-UI streaming/input of Phases 2-4.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposePreviewRemoteRenderSpike
 *     adb logcat -d -s ComposeRemoteSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewRemoteRenderSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("ComposeRemoteSpike", m); println(m) }

    /** `fun Preview() { Text("Hello") }` lowered — a material3 Text against the bundled runtime (the flip). */
    private fun textPreview(): LoweredComposePreview {
        val span = SourceSpan(0, 0)
        val call = RNode.Call(
            ResolvedCallable.Library(
                "Text", "androidx.compose.material3.TextKt", "Text", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const("Hello", null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        return LoweredComposePreview(ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(call), false, span), emptyList()), emptyMap())
    }

    @Test
    fun rendersAStaticPreviewOutOfProcess() {
        val client = ComposePreviewRemoteClient(instrumentation.targetContext)
        client.warmUp() // fork + bind :preview ahead of the render

        val frame = client.renderOnce(
            lowered = textPreview(),
            widthPx = 320, heightPx = 160, density = 2.0f, night = false,
        )

        val myPid = Process.myPid()
        log("REMOTE: myPid=$myPid, frame=${frame?.let { "pid=${it.remotePid} ${it.bitmap.width}x${it.bitmap.height}" } ?: "null"}")

        assertNotNull("no frame came back from :preview (bind or render failed)", frame)
        frame!!
        assertNotEquals("the frame was NOT rendered in a separate process — isolation failed", myPid, frame.remotePid)

        // Zero-copy frames arrive as HARDWARE bitmaps (getPixels throws) — copy to software first.
        val bmp = if (frame.bitmap.config == android.graphics.Bitmap.Config.HARDWARE)
            frame.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else frame.bitmap
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val drew = px.any { it != px[0] }
        log("REMOTE: non-uniform=$drew (the preview drew content out-of-process)")
        assertTrue("the out-of-process frame is blank (uniform) — the preview didn't draw", drew)
    }
}
