package dev.ide.android.spike

import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.interpretedClassNameOf
import dev.ide.preview.realview.VmViewFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * On-device (ART) proof that the real-view layout preview can create a custom View by INTERPRETING its
 * bytecode with the `:jvm-interp` VM instead of dexing it onto a class loader. The fixture
 * `spikefixture.InterpretedLabel` extends the real `android.widget.TextView`; its class bytes are bundled as an
 * asset (never dexed, never loaded by ART). [VmViewFactory] realizes it as a generated peer — a real
 * `TextView` subclass whose overridden methods dispatch into the interpreter — so the framework holds,
 * measures, and draws it like any view.
 *
 * A passing render exercises the whole path: peer construction, threading the interpreted `super(Context,
 * AttributeSet)` into the real TextView constructor (which calls overridable methods, the ctor-reentrancy case
 * the peer factory must survive), a bridged `setText` call, and reading an interpreted private method.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmViewFactoryArtSpike
 *     adb logcat -d -s VmViewFactoryArt
 */
@RunWith(AndroidJUnit4::class)
class VmViewFactoryArtSpike {

    private fun log(message: String) {
        Log.i("VmViewFactoryArt", message)
        println(message)
    }

    @Test
    fun interpretedCustomViewInflatesAndDraws() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val assets = instr.context.assets
        val appContext = instr.targetContext

        // Serve only the fixture's bytes; the factory's policy interprets it (not boot-loadable) and bridges the
        // real TextView super. No classpath jars/dirs are needed with the source seam supplied.
        val source = ClassBytesSource { name ->
            if (name == "spikefixture/InterpretedLabel") {
                runCatching { assets.open("vmview/InterpretedLabel.class").use { it.readBytes() } }.getOrNull()
            } else null
        }
        val factory = VmViewFactory(classpath = emptyList(), peerFactory = DexPeerFactory(), source = source)

        // View construction + draw on the main thread (some framework views assert a Looper on attach/animate).
        val holder = arrayOfNulls<Any>(1)
        instr.runOnMainSync {
            holder[0] = runCatching {
                val view = factory.createView("spikefixture.InterpretedLabel", appContext, null)
                    ?: error("factory returned null for the interpreted view")
                assertTrue("peer is-a TextView", view is TextView)
                assertEquals("bridged setText + interpreted badge()", "interpreted:OK", (view as TextView).text.toString())
                assertEquals(
                    "hierarchy reports the interpreted class, not the synthetic peer name",
                    "spikefixture.InterpretedLabel", interpretedClassNameOf(view),
                )
                val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                view.measure(spec, spec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val bmp = createBitmap(max(1, view.measuredWidth), max(1, view.measuredHeight))
                bmp.eraseColor(Color.WHITE)
                view.draw(Canvas(bmp))
                view
            }
        }
        factory.close()

        @Suppress("UNCHECKED_CAST")
        val result = holder[0] as Result<View>
        result.exceptionOrNull()?.let { throw it }
        val view = result.getOrThrow()
        assertNotNull(view)
        log("interpreted view '${interpretedClassNameOf(view)}' peer=${view.javaClass.name} text='${(view as TextView).text}' size=${view.measuredWidth}x${view.measuredHeight}")
    }
}
