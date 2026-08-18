package dev.ide.android.spike

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.SwingRunRemoteClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device proof of the whole device path for a Swing run: [SwingRunRemoteClient] in the IDE process starts a
 * program in `:preview`, receives its window as streamed frames, forwards a tap, and is told when it exits.
 *
 * [AwtToolkitArtSpike] proved the toolkit renders an interpreted program on ART, but in-process and with no
 * IPC. This one drives exactly what the Run pane will drive, and asserts on the pid, so a pass means the
 * program really ran somewhere the IDE's own crash cannot follow.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.SwingRunSessionArtSpike
 *     adb logcat -d -s SwingRunArt
 */
@RunWith(AndroidJUnit4::class)
class SwingRunSessionArtSpike {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = SwingRunRemoteClient(context)
    private var session: SwingRunRemoteClient.Session? = null

    private fun log(message: String) {
        Log.i("SwingRunArt", message)
        println(message)
    }

    @After
    fun stop() {
        session?.stop()
    }

    @Test
    fun aSwingProgramRunsInThePreviewProcessAndStreamsItsWindow() {
        val classpath = stageProgramClasspath()
        val frameDir = File(context.cacheDir, "swing-run-frames").apply { deleteRecursively() }

        val remotePid = client.remotePid()
        assertNotEquals("the program must not run in the IDE's own process", Process.myPid(), remotePid)
        log("bound :preview pid=$remotePid (test pid=${Process.myPid()})")

        val host = RecordingHost()
        val run = client.start(
            classpath = listOf(classpath.path),
            mainClass = "swingfixture.SwingFixture",
            args = emptyList(),
            widthPx = WIDTH,
            heightPx = HEIGHT,
            frameDir = frameDir,
            host = host,
        )
        assertNotNull("the client could not start the program", run)
        session = run

        // 1. The program built its UI and the client decoded the first frame.
        assertTrue("no frame streamed back", host.awaitFrameAfter(-1, STARTUP_TIMEOUT_MS))
        val first = host.latest()!!
        assertEquals(WIDTH, first.bitmap.width)
        assertEquals(HEIGHT, first.bitmap.height)
        log("first frame: seq=${first.seq} ${first.bitmap.width}x${first.bitmap.height}")

        // The interpreted paintComponent filled a 50x20 rect in its own colour at (10,10) of the centre panel,
        // which BorderLayout puts at the top of the frame.
        assertEquals(
            "the streamed pixels are the program's drawing",
            FIXTURE_BLUE, first.bitmap.getPixel(30, 20),
        )

        // 2. A forwarded tap crosses back into the program and changes what it paints.
        run!!.pointer(POINTER_DOWN, BUTTON_X, BUTTON_Y)
        run.pointer(POINTER_UP, BUTTON_X, BUTTON_Y)
        assertTrue("no frame after the tap", host.awaitFrameAfter(first.seq, INPUT_TIMEOUT_MS))
        log("frame after the tap: seq=${host.latest()!!.seq}")

        // 3. Stopping ends the run, and the client is told.
        run.stop()
        assertTrue("the client was not told the run ended", host.awaitExit(EXIT_TIMEOUT_MS))
        log("exited with code ${host.exitCode.get()} '${host.exitError.get()}'")
        session = null
    }

    /** The fixture's class files, unpacked from the test assets into a directory the session can read. */
    private fun stageProgramClasspath(): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val out = File(context.cacheDir, "swing-run-classes/swingfixture").apply { deleteRecursively(); mkdirs() }
        for (name in assets.list("awt").orEmpty()) {
            assets.open("awt/$name").use { input -> File(out, name).outputStream().use { input.copyTo(it) } }
        }
        // The classpath root is the parent: the VM resolves `swingfixture/SwingFixture.class` beneath it.
        return out.parentFile!!
    }

    /** Collects what the client hands the Run pane. */
    private class RecordingHost : SwingRunRemoteClient.Host {

        class Frame(val bitmap: Bitmap, val seq: Long)

        private val latest = AtomicReference<Frame?>()
        private val exited = CountDownLatch(1)
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val exitError = AtomicReference("")
        val output = StringBuilder()

        fun latest(): Frame? = latest.get()

        override fun onFrame(bitmap: Bitmap, seq: Long) {
            latest.set(Frame(bitmap, seq))
        }

        override fun onOutput(text: String) {
            synchronized(output) { output.append(text) }
        }

        override fun onExited(exitCode: Int, error: String) {
            this.exitCode.set(exitCode)
            exitError.set(error)
            exited.countDown()
        }

        /** Wait for a frame newer than [seq], which is how "the UI changed" is observed from outside. */
        fun awaitFrameAfter(seq: Long, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if ((latest.get()?.seq ?: -1) > seq) return true
                Thread.sleep(20)
            }
            return false
        }

        fun awaitExit(timeoutMs: Long): Boolean = exited.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 160

        /** The fixture's button is the SOUTH child of a 160-tall frame; a tap near the bottom hits it. */
        const val BUTTON_X = 120f
        const val BUTTON_Y = 146f

        /** `new Color(0x2D, 0x6C, 0xDF)` in the fixture, opaque. */
        const val FIXTURE_BLUE = 0xFF2D6CDF.toInt()

        // Mirrors dev.ide.build.engine.RunPointer.
        const val POINTER_DOWN = 0
        const val POINTER_UP = 2

        const val STARTUP_TIMEOUT_MS = 20_000L
        const val INPUT_TIMEOUT_MS = 10_000L
        const val EXIT_TIMEOUT_MS = 10_000L
    }
}
