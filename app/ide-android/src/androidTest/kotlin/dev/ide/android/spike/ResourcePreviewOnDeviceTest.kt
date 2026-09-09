package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidPreviewResources
import dev.ide.android.support.resources.ResourceModel
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.ui.editor.preview.UiDrawablePainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * On-ART end-to-end proof of the interpreter-mediated Compose-preview RESOURCE resolution: it runs the REAL
 * production resolver ([AndroidPreviewResources]) over a REAL [dev.ide.android.support.resources.ResourceRepository]
 * (parsed from a temp `res/`) inside a real composition, and asserts `R.string`/`stringResource`/`colorResource`/
 * `painterResource` (incl. a `<vector>`) resolve to the project's values — plus the `key { }` intrinsic. Runs on
 * device because the value-class ABI (Color/Dp) + reflection differ from the desktop JVM the unit tests use.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ResourcePreviewOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class ResourcePreviewOnDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val span = SourceSpan(0, 0)
    private val keySeq = AtomicInteger(1)

    @Before fun reset() { OnDeviceCapture.reset() }

    @Test
    fun projectResourcesResolveOnArt() {
        val resDir = writeTempRes()
        val repo = ResourceModel.DEFAULT.parse(listOf(resDir.toPath()))
        assertTrue("temp res should parse", !repo.isEmpty())
        val resolver = AndroidPreviewResources(repo, NAMESPACE, density = 2f, night = false)

        // @Composable fun Preview() {
        //     RecordText(stringResource(R.string.greeting))
        //     key("k") { RecordText("keyed") }
        //     RecordColor(colorResource(R.color.brand))
        //     RecordPainter(painterResource(R.drawable.ic_test))
        // }
        val body = RNode.Block(
            listOf(
                record("RecordText", stringResource(rField("string", "greeting"))),
                keyOf(record("RecordText", constStr("keyed"))),
                record("RecordColor", colorResource(rField("color", "brand"))),
                record("RecordPainter", painterResource(rField("drawable", "ic_test"))),
            ),
            isExpression = false, source = span,
        )
        val preview = ResolvedFunction("Preview", emptyList(), body, emptyList())

        val error = AtomicReference<Throwable?>(null)
        val renderer = ComposePreviewRenderer(resources = resolver)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { renderer.Render(preview, emptyMap(), onError = { error.set(it) }) }
            }
            instrumentation.waitForIdleSync()
        }

        assertNull("preview render threw on ART: ${error.get()}", error.get())
        assertTrue("stringResource(R.string.greeting) → project value; got ${OnDeviceCapture.texts}", "Hello device" in OnDeviceCapture.texts)
        assertTrue("key { } intrinsic on ART; got ${OnDeviceCapture.texts}", "keyed" in OnDeviceCapture.texts)
        assertEquals("colorResource(R.color.brand)", 0xFFEE1122.toInt(), OnDeviceCapture.colorArgb)
        assertTrue("painterResource(R.drawable.ic_test) → a vector Painter; got ${OnDeviceCapture.painter}", OnDeviceCapture.painter is UiDrawablePainter)
        Log.i(TAG, "on-device resources OK: texts=${OnDeviceCapture.texts} color=#${Integer.toHexString(OnDeviceCapture.colorArgb)} painter=${OnDeviceCapture.painter?.javaClass?.simpleName}")
    }

    // --- lowered-tree builders (what the resolver/lowerer would emit) ---

    /** `R.<type>.<name>` — the nested PropertyGet whose outer binding owner is the synthetic R subclass. */
    private fun rField(type: String, name: String) = RNode.PropertyGet(
        RNode.PropertyGet(
            RNode.Name(Binding.ObjectRef("$NAMESPACE.R", "R"), span),
            Binding.Property(type, "$NAMESPACE.R", backingField = false), span,
        ),
        Binding.Property(name, "$NAMESPACE.R.$type", backingField = false), span,
    )

    private fun stringResource(id: RNode) = resCall("stringResource", "StringResources_androidKt", id)
    private fun colorResource(id: RNode) = resCall("colorResource", "ColorResources_androidKt", id)
    private fun painterResource(id: RNode) = resCall("painterResource", "PainterResources_androidKt", id)

    private fun resCall(name: String, facade: String, id: RNode) = RNode.Call(
        lib(name, "androidx.compose.ui.res.$facade"), DispatchKind.TOP_LEVEL, receiver = null,
        args = listOf(RArg(id)), callSiteKey = CallSiteKey(keySeq.getAndIncrement()), source = span,
    )

    /** A `RecordX(arg)` call into the fake on-device recorder composable. */
    private fun record(fn: String, arg: RNode) = RNode.Call(
        lib(fn, "dev.ide.android.spike.ResourcePreviewOnDeviceTestKt"), DispatchKind.TOP_LEVEL, receiver = null,
        args = listOf(RArg(arg)), callSiteKey = CallSiteKey(keySeq.getAndIncrement()), source = span,
    )

    /** `key("k") { body }`. */
    private fun keyOf(body: RNode) = RNode.Call(
        lib("key", "androidx.compose.runtime.ComposablesKt"), DispatchKind.TOP_LEVEL, receiver = null,
        args = listOf(
            RArg(constStr("k")),
            RArg(RNode.Lambda(emptyList(), RNode.Block(listOf(body), isExpression = false, span), emptyList(), span)),
        ),
        callSiteKey = CallSiteKey(keySeq.getAndIncrement()), source = span,
    )

    private fun constStr(s: String) = RNode.Const(s, null, span)

    private fun lib(name: String, owner: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = owner, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = false, isComposable = true,
    )

    private fun writeTempRes(): File {
        val res = File(instrumentation.targetContext.cacheDir, "preview-res-test/res")
        res.deleteRecursively()
        File(res, "values").mkdirs()
        File(res, "values/strings.xml").writeText("""<resources><string name="greeting">Hello device</string></resources>""")
        File(res, "values/colors.xml").writeText("""<resources><color name="brand">#FFEE1122</color></resources>""")
        File(res, "drawable").mkdirs()
        File(res, "drawable/ic_test.xml").writeText(
            """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF00FF00" android:pathData="M0,0 L24,0 L24,24 L0,24 Z"/></vector>""",
        )
        return res
    }

    private companion object {
        const val TAG = "ResourcePreviewOnDevice"
        const val NAMESPACE = "com.example.app"
    }
}

/** Fake recorder composables — capture what the interpreted resource calls resolved to, so the test asserts the
 *  actual values (not just "it composed"). Real @Composables compiled by the app's Compose. */
object OnDeviceCapture {
    val texts = ArrayList<String>()
    var colorArgb: Int = 0
    var painter: Any? = null
    fun reset() { texts.clear(); colorArgb = 0; painter = null }
}

@Composable fun RecordText(text: String) { OnDeviceCapture.texts.add(text) }

@Composable fun RecordColor(color: Color) { OnDeviceCapture.colorArgb = color.toArgb() }

@Composable fun RecordPainter(painter: Painter) { OnDeviceCapture.painter = painter }
