package dev.ide.android.spike

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.ComposePreviewRemoteClient
import dev.ide.interp.SandboxCategory
import dev.ide.interp.SandboxFinding
import dev.ide.core.LoweredComposePreview
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference as Ref
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An out-of-process preview whose content lambda fails must REPORT it. `ComposePreviewSessionService` passed
 * `{}` as `ComposePreviewRenderer.Render`'s `onPartialError`, so a failure inside deferred content was
 * swallowed in `:preview` and the IDE showed no problem at all — while the identical preview rendered
 * in-process warned "Preview partially rendered".
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposePreviewPartialErrorSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewPartialErrorSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("ComposePartialSpike", m); println(m) }

    private val span = SourceSpan(0, 0)

    /** A call to a class that does not exist — the interpreter throws when it tries to dispatch it. */
    private fun throwingCall() = RNode.Call(
        ResolvedCallable.Library(
            "definitelyNotAThing", "dev.ide.does.not.Exist", "definitelyNotAThing", emptyList(),
            isStatic = true, isConstructor = false, isInline = false,
        ),
        DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(),
        callSiteKey = CallSiteKey(99), source = span,
    )

    /**
     * `SideEffect { <throws> }` — a COMPOSABLE call whose lambda parameter is NOT composable, so
     * `ComposeDispatcher` hands it to `guardedLambdaProxy`, which records the failure in `contentLambdaError`
     * and degrades instead of throwing. That is precisely the class of failure the isolated path used to drop:
     * the preview still draws, and the user should still be told part of it didn't run.
     */
    private fun preview(): LoweredComposePreview {
        val effect = RNode.Call(
            ResolvedCallable.Library(
                "SideEffect", "androidx.compose.runtime.EffectsKt", "SideEffect", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                RArg(RNode.Lambda(emptyList(), RNode.Block(listOf(throwingCall()), false, span), emptyList(), span)),
            ),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val text = RNode.Call(
            ResolvedCallable.Library(
                "Text", "androidx.compose.material3.TextKt", "Text", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const("hi", null, span))),
            callSiteKey = CallSiteKey(2), source = span,
        )
        return LoweredComposePreview(
            ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(effect, text), false, span), emptyList()),
            emptyMap(),
        )
    }

    /** `FileInputStream("/nope")` — an owner every member of which the sandbox classifies as file access. */
    private fun fileReadingPreview(): LoweredComposePreview {
        val read = RNode.Call(
            ResolvedCallable.Library(
                "FileInputStream", "java.io.FileInputStream", "<init>", listOf(null),
                isStatic = false, isConstructor = true, isInline = false,
            ),
            DispatchKind.CONSTRUCTOR, receiver = null,
            args = listOf(RArg(RNode.Const("/nope", null, span))),
            callSiteKey = CallSiteKey(3), source = span,
        )
        val text = RNode.Call(
            ResolvedCallable.Library(
                "Text", "androidx.compose.material3.TextKt", "Text", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const("hi", null, span))),
            callSiteKey = CallSiteKey(4), source = span,
        )
        return LoweredComposePreview(
            ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(read, text), false, span), emptyList()),
            emptyMap(),
        )
    }

    /**
     * The preview sandbox must be ENFORCED and REPORTED out-of-process. `ComposePreviewSessionService` built its
     * renderer with no `hooks` at all — and the renderer's default is null = unrestricted — so with isolation on
     * by default the project's sandbox settings were silently inert, and blocked calls (which are stubbed, so the
     * preview still looks fine) had no way to reach the chip.
     */
    @Test
    fun sandboxIsEnforcedAndReportedOutOfProcess() {
        val client = ComposePreviewRemoteClient(instrumentation.targetContext)
        client.warmUp()

        val found = Ref<List<SandboxFinding>>(emptyList())
        val sink = object : ComposePreviewRemoteClient.FrameSink {
            override fun onFrame(bitmap: Bitmap, seq: Long) {}
            override fun onError(message: String) { log("onError: $message") }
            override fun onSandboxFindings(findings: List<SandboxFinding>) {
                if (findings.isNotEmpty()) found.set(findings)
                log("onSandboxFindings: $findings")
            }
        }

        val session = client.openSession(
            lowered = fileReadingPreview(), widthPx = 320, heightPx = 160, density = 2.0f, night = false,
            sink = sink, sandbox = arrayOf(SandboxCategory.FILE_IO.id),
        )
        assertNotNull("could not open a :preview session (bind or open failed)", session)
        session!!
        try {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && found.get().isEmpty()) Thread.sleep(100)
            log("findings=${found.get()}")
            assertTrue(
                "the blocked file-access call must be reported over the sandbox channel (got ${found.get()})",
                found.get().any { it.category == SandboxCategory.FILE_IO },
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun contentLambdaFailureIsReportedOutOfProcess() {
        val client = ComposePreviewRemoteClient(instrumentation.targetContext)
        client.warmUp()

        val frames = AtomicInteger(0)
        val fatal = AtomicReference<String?>(null)
        val partial = AtomicReference<String?>(null)
        val sink = object : ComposePreviewRemoteClient.FrameSink {
            override fun onFrame(bitmap: Bitmap, seq: Long) { frames.incrementAndGet() }
            override fun onError(message: String) { fatal.set(message); log("onError: $message") }
            override fun onPartialError(message: String?) { partial.set(message); log("onPartialError: $message") }
        }

        val session = client.openSession(
            lowered = preview(), widthPx = 320, heightPx = 160, density = 2.0f, night = false, sink = sink,
        )
        assertNotNull("could not open a :preview session (bind or open failed)", session)
        session!!
        try {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && partial.get() == null) Thread.sleep(100)
            log("frames=${frames.get()} fatal=${fatal.get()} partial=${partial.get()}")
            assertTrue(
                "the failing SideEffect body must be reported over the partial-error channel " +
                    "(frames=${frames.get()}, fatal=${fatal.get()})",
                partial.get() != null,
            )
        } finally {
            session.close()
        }
    }
}
