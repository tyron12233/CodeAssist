package dev.ide.android.spike

import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-preview isolation **Phase 2**: STREAM frames from a persistent out-of-process session, and prove a
 * live edit re-renders remotely. It opens a [ComposePreviewRemoteClient] session on a `Text("Hello")` preview,
 * waits for the first streamed frame, then [ComposePreviewRemoteClient.Session.update]s to `Text("World")` and
 * waits for a NEW frame whose pixels differ — all from a DIFFERENT pid than the test process. This is the
 * session + streaming + live-edit contract the live UI is rewired onto (input forwarding + the hang watchdog are
 * later phases).
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposePreviewStreamingSpike
 *     adb logcat -d -s ComposeStreamSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewStreamingSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("ComposeStreamSpike", m); println(m) }

    /** `fun Preview() { Text(text) }` lowered — a material3 Text against the bundled runtime (the flip). */
    private fun textPreview(text: String): LoweredComposePreview {
        val span = SourceSpan(0, 0)
        val call = RNode.Call(
            ResolvedCallable.Library(
                "Text", "androidx.compose.material3.TextKt", "Text", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(text, null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        return LoweredComposePreview(ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(call), false, span), emptyList()), emptyMap())
    }

    @Test
    fun streamsFramesAndLiveEditsOutOfProcess() {
        val client = ComposePreviewRemoteClient(instrumentation.targetContext)
        client.warmUp()

        val latest = AtomicReference<Bitmap?>(null)
        val frameCount = AtomicInteger(0)
        val errors = AtomicReference<String?>(null)
        val sink = object : ComposePreviewRemoteClient.FrameSink {
            override fun onFrame(bitmap: Bitmap, seq: Long) { latest.set(bitmap); frameCount.incrementAndGet() }
            override fun onError(message: String) { errors.set(message) }
        }

        val session = client.openSession(
            lowered = textPreview("Hello"),
            widthPx = 320, heightPx = 160, density = 2.0f, night = false, sink = sink,
        )
        assertNotNull("could not open a :preview session (bind or open failed)", session)
        session!!

        try {
            waitUntil("first streamed frame") { frameCount.get() >= 1 }
            val helloFrame = latest.get()
            log("STREAM: myPid=${Process.myPid()} remotePid=${session.remotePid} frame1=${helloFrame?.let { "${it.width}x${it.height}" }}")
            assertNotNull("no frame streamed for the initial preview", helloFrame)
            assertNotEquals("frames are NOT coming from a separate process", Process.myPid(), session.remotePid)
            val helloPixels = pixels(helloFrame!!)

            // Live edit: push a different program; the running session must re-render → a new, different frame.
            val framesBefore = frameCount.get()
            session.update(textPreview("World"))
            waitUntil("frame after live edit") { frameCount.get() > framesBefore && latest.get()?.let { !pixels(it).contentEquals(helloPixels) } == true }
            val worldFrame = latest.get()!!
            val changed = !pixels(worldFrame).contentEquals(helloPixels)
            log("STREAM: framesTotal=${frameCount.get()}, liveEditChangedFrame=$changed, error=${errors.get()}")

            assertTrue("the live edit did not stream a new frame", frameCount.get() > framesBefore)
            assertTrue("the live edit produced an identical frame (update did not re-render remotely)", changed)
        } finally {
            session.close()
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun waitUntil(what: String, deadlineMs: Long = 8_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(25)
        }
        log("timed out waiting for: $what")
    }
}
