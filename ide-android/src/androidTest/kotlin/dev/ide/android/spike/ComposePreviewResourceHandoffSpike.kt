package dev.ide.android.spike

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.ComposePreviewRemoteClient
import dev.ide.core.LoweredComposePreview
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-preview isolation resource handoff: prove `stringResource(R.string.x)` resolves against the PROJECT'S
 * resources OUT-OF-PROCESS. `:preview` can't receive the IDE's in-memory `ResourceRepository`, so it's handed the
 * module's res-dir paths + R namespace and rebuilds the repository itself. This renders `Text(stringResource(
 * R.string.greeting))` twice — with the res root (a temp `res/values/strings.xml`) and without — and asserts the
 * two frames DIFFER (the string resolved and drew) and the with-resources frame is non-blank. Closes the gap that
 * made `R.string.rgb_instruction` fail with "the module's resources didn't load" under the isolation toggle.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposePreviewResourceHandoffSpike
 *     adb logcat -d -s ComposeResHandoff
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewResourceHandoffSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("ComposeResHandoff", m); println(m) }
    private val span = SourceSpan(0, 0)

    /** `fun Preview() { Text(stringResource(R.string.greeting)) }` lowered (the nested R PropertyGet chain). */
    private fun stringResPreview(): LoweredComposePreview {
        val rGreeting = RNode.PropertyGet(
            RNode.PropertyGet(
                RNode.Name(Binding.ObjectRef("com.example.R", "R"), span),
                Binding.Property("string", "com.example.R", backingField = false, isExtension = false), span,
            ),
            Binding.Property("greeting", "com.example.R.string", backingField = false, isExtension = false), span,
        )
        val stringRes = RNode.Call(
            ResolvedCallable.Library(
                "stringResource", "androidx.compose.ui.res.StringResources_androidKt", "stringResource", emptyList(),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(rGreeting)), callSiteKey = CallSiteKey(1), source = span,
        )
        val text = RNode.Call(
            ResolvedCallable.Library(
                "Text", "androidx.compose.material3.TextKt", "Text", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(stringRes)), callSiteKey = CallSiteKey(2), source = span,
        )
        return LoweredComposePreview(ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(text), false, span), emptyList()), emptyMap())
    }

    @Test
    fun resolvesProjectStringResourceOutOfProcess() {
        val ctx = instrumentation.targetContext
        val resDir = File(ctx.cacheDir, "res-handoff-test/res")
        File(resDir, "values").mkdirs()
        File(resDir, "values/strings.xml").writeText(
            "<resources><string name=\"greeting\">Hi from project resources</string></resources>",
        )

        val client = ComposePreviewRemoteClient(ctx)
        client.warmUp()
        val preview = stringResPreview()

        // WITH the res root: :preview rebuilds the repository, R.string.greeting resolves, Text draws.
        val withRes = client.renderOnce(preview, 360, 120, 2.0f, false, resRoots = arrayOf(resDir.path), namespace = "com.example")
        // WITHOUT it: the unresolved R.string.greeting is fatal for this whole preview → no frame at all. The
        // contrast is the proof — the ONLY difference is the resolver, so the resolver is what made it render.
        val without = client.renderOnce(preview, 360, 120, 2.0f, false)

        log("RES-HANDOFF: withRes=${withRes?.let { "pid=${it.remotePid}" }}, without=${if (without == null) "no-frame (fatal, as expected)" else "rendered"}")
        assertNotNull("no frame WITH resources — the resource handoff failed", withRes)

        val a = pixels(withRes!!.bitmap)
        val drew = a.any { it != a[0] }
        log("RES-HANDOFF: withRes non-blank=$drew")
        assertTrue("the with-resources frame is blank — stringResource(R.string.greeting) didn't draw", drew)
        assertNull("the no-resources render unexpectedly produced a frame (R.string.greeting should be unresolvable without the handoff)", without)
    }

    // Zero-copy frames arrive as HARDWARE bitmaps (getPixels throws on those) — copy to software first.
    private fun pixels(b: Bitmap): IntArray {
        val sw = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        return IntArray(sw.width * sw.height).also { sw.getPixels(it, 0, sw.width, 0, 0, sw.width, sw.height) }
    }
}
