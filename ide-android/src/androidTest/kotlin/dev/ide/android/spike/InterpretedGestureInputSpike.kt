package dev.ide.android.spike

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import dev.ide.core.LoweredComposePreview
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Records how many times the interpreted `detectDragGestures { onDrag }` callback ran — the observable. */
object GestureProbe {
    val drags = AtomicInteger(0)
    fun reset() { drags.set(0) }
}

/** Called by the interpreted `onDrag` callback (a TOP_LEVEL library call the interpreter dispatches). */
fun bumpDrag() { GestureProbe.drags.incrementAndGet() }

/**
 * End-to-end proof that an INTERPRETED `Modifier.pointerInput { detectDragGestures {...} }` responds to real
 * touches in the off-screen preview surface — the fix for the reported "can't swipe the 2048 board" bug. The
 * previewed composable is INTERPRETED (`Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectDragGestures {
 * _, _ -> bumpDrag() } })`), rendered by [ComposePreviewRenderer] on a real off-screen `ComposeView`; a
 * DOWN → MOVE… → UP drag is forwarded via [OffscreenComposeSurface.dispatchTouch], and the interpreted `onDrag`
 * callback must fire. Before the suspend-`$default` dispatch fix, `detectDragGestures` (a suspend function with
 * defaulted callbacks) failed to dispatch and the whole gesture block was swallowed, so the drag did nothing.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.InterpretedGestureInputSpike
 */
@RunWith(AndroidJUnit4::class)
class InterpretedGestureInputSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("InterpGestureInput", m); println(m) }
    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.android.spike.InterpretedGestureInputSpikeKt"

    /** `Spacer(Modifier.fillMaxSize(1f).pointerInput(Unit) { detectDragGestures { _, _ -> bumpDrag() } })`. */
    private fun gesturePreview(): LoweredComposePreview {
        val scopeSlot = SlotId(0) // the pointerInput block's PointerInputScope receiver

        val bump = RNode.Call(
            ResolvedCallable.Library("bumpDrag", facade, "bumpDrag", emptyList(), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(1), source = span,
        )
        // detectDragGestures { change, dragAmount -> bumpDrag() } — onDragStart/onDragEnd/onDragCancel defaulted.
        val onDrag = RNode.Lambda(
            listOf(RParam(SlotId(1), "change", null), RParam(SlotId(2), "amount", null)),
            RNode.Block(listOf(bump), isExpression = false, span), emptyList(), span,
        )
        val detect = RNode.Call(
            ResolvedCallable.Library(
                "detectDragGestures", "androidx.compose.foundation.gestures.DragGestureDetectorKt", "detectDragGestures",
                listOf(null), isStatic = true, isConstructor = false, isInline = false, isSuspend = true,
            ),
            DispatchKind.EXTENSION, receiver = RNode.Name(Binding.Param(scopeSlot, "scope"), span),
            args = listOf(RArg(onDrag, trailingLambda = true)), callSiteKey = CallSiteKey(2), source = span,
        )
        val block = RNode.Lambda(listOf(RParam(scopeSlot, "scope", null)), RNode.Block(listOf(detect), isExpression = false, span), emptyList(), span)

        val modifier = RNode.Const(androidx.compose.ui.Modifier, null, span)
        val fillMaxSize = RNode.Call(
            ResolvedCallable.Library("fillMaxSize", "androidx.compose.foundation.layout.SizeKt", "fillMaxSize",
                listOf(KotlinType("kotlin.Float")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.EXTENSION, receiver = modifier, args = listOf(RArg(RNode.Const(1f, KotlinType("kotlin.Float"), span))),
            callSiteKey = CallSiteKey(3), source = span,
        )
        val pointerInput = RNode.Call(
            ResolvedCallable.Library("pointerInput", "androidx.compose.ui.input.pointer.SuspendingPointerInputFilterKt", "pointerInput",
                listOf(null, null), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.EXTENSION, receiver = fillMaxSize,
            args = listOf(RArg(RNode.Const(Unit, null, span)), RArg(block, trailingLambda = true)),
            callSiteKey = CallSiteKey(4), source = span,
        )
        val spacer = RNode.Call(
            ResolvedCallable.Library("Spacer", "androidx.compose.foundation.layout.SpacerKt", "Spacer",
                listOf(null), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(pointerInput)),
            callSiteKey = CallSiteKey(5), source = span,
        )
        return LoweredComposePreview(ResolvedFunction("GesturePreview", emptyList(), RNode.Block(listOf(spacer), false, span), emptyList()), emptyMap())
    }

    // Two fixes made this pass (it was the reported "can't swipe the 2048 board" bug):
    //  1. `detectDragGestures` is a suspend function with defaulted callbacks; its `$default` synthetic carries a
    //     `Continuation` between the reals and the mask+marker, which the interpreter's default-arg dispatch
    //     mis-slotted — so the call threw and the gesture block was swallowed (`Dispatcher.kt` +
    //     `GestureDefaultSyntheticTest`).
    //  2. Even dispatched, an interpreted `pointerInput { }` block ran on the coroutine bridge's background fiber,
    //     so `detectDragGestures` was a DETACHED coroutine and Compose (which delivers pointer events only to a
    //     detector inside the node's OWN coroutine) never drove it. Fixed with a shallow SUSPENDABLE-interpreter
    //     step: a tail-suspendable pointer block (`{ <sync…>, detectXGestures(…) }`) runs its prefix on the
    //     node's calling thread and hands the trailing suspend call the node's own continuation
    //     (`InterpretedLambda.invokeSuspendTail` → `dispatchSuspend`'s tail handoff), so the detector IS the
    //     node's coroutine. The compiled controls below fire the same 5 drags through the identical harness.
    @Test
    fun interpretedDetectDragGesturesReceivesAForwardedDrag() {
        GestureProbe.reset()
        val preview = gesturePreview()
        val ctx = instrumentation.targetContext
        val w = 300
        val h = 300
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val frames = AtomicInteger(0)
        val renderError = AtomicReference<Throwable?>(null)
        surface.onFrame = { frames.incrementAndGet() }
        try {
            surface.start {
                val onErr: @androidx.compose.runtime.Composable (Throwable) -> Unit = { t -> renderError.set(t) }
                ComposePreviewRenderer().Render(preview.entry, preview.program, preview.classes, emptyList(), onErr) {}
            }
            waitUntil("first frame") { frames.get() > 0 }
            // The pointerInput block is launched off-thread; give it a beat to reach awaitPointerEvent.
            SystemClock.sleep(300)

            // A drag from left-centre to right-centre — DOWN, several MOVEs, UP.
            surface.dispatchTouch(MotionEvent.ACTION_DOWN, 60f, 150f, 0)
            for (x in intArrayOf(90, 130, 170, 210, 240)) {
                surface.dispatchTouch(MotionEvent.ACTION_MOVE, x.toFloat(), 150f, 0)
                SystemClock.sleep(16)
            }
            surface.dispatchTouch(MotionEvent.ACTION_UP, 240f, 150f, 0)

            waitUntil("drag callback fired") { GestureProbe.drags.get() > 0 }
            log("INTERP-GESTURE: drags=${GestureProbe.drags.get()}, renderError=${renderError.get()?.message}")
            assertTrue("the interpreted preview threw: ${renderError.get()?.message}", renderError.get() == null)
            assertTrue("the interpreted detectDragGestures onDrag never fired for a forwarded drag", GestureProbe.drags.get() > 0)
        } finally {
            runCatching { surface.close() }
        }
    }

    /** Control: the SAME harness with a REAL (compiled) `detectDragGestures` — isolates a harness/layout/touch
     *  problem (fails here too) from an interpreter-only one (passes here, fails interpreted). */
    @Test
    fun compiledDetectDragGesturesReceivesAForwardedDrag() {
        GestureProbe.reset()
        val ctx = instrumentation.targetContext
        val w = 300; val h = 300
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val frames = AtomicInteger(0)
        surface.onFrame = { frames.incrementAndGet() }
        try {
            surface.start {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectDragGestures { _, _ -> bumpDrag() } })
            }
            waitUntil("first frame") { frames.get() > 0 }
            SystemClock.sleep(300)
            surface.dispatchTouch(MotionEvent.ACTION_DOWN, 60f, 150f, 0)
            for (x in intArrayOf(90, 130, 170, 210, 240)) {
                surface.dispatchTouch(MotionEvent.ACTION_MOVE, x.toFloat(), 150f, 0); SystemClock.sleep(16)
            }
            surface.dispatchTouch(MotionEvent.ACTION_UP, 240f, 150f, 0)
            waitUntil("compiled drag fired") { GestureProbe.drags.get() > 0 }
            log("COMPILED-GESTURE: drags=${GestureProbe.drags.get()}")
            assertTrue("the COMPILED detectDragGestures onDrag never fired — harness/layout/touch problem, not the interpreter", GestureProbe.drags.get() > 0)
        } finally {
            runCatching { surface.close() }
        }
    }

    /** Isolation: the compiled gesture wrapped exactly like the interpreter's render path — `Spacer` (not `Box`)
     *  under `LocalInspectionMode = true`. If this fires, layout + inspection mode are fine and the interpreted
     *  gap is purely the coroutine identity; if it doesn't, one of those is the cause. */
    @Test
    fun compiledGestureUnderInspectionModeAndSpacer() {
        GestureProbe.reset()
        val ctx = instrumentation.targetContext
        val w = 300; val h = 300
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val frames = AtomicInteger(0)
        surface.onFrame = { frames.incrementAndGet() }
        try {
            surface.start {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectDragGestures { _, _ -> bumpDrag() } })
                }
            }
            waitUntil("first frame") { frames.get() > 0 }
            SystemClock.sleep(300)
            surface.dispatchTouch(MotionEvent.ACTION_DOWN, 60f, 150f, 0)
            for (x in intArrayOf(90, 130, 170, 210, 240)) {
                surface.dispatchTouch(MotionEvent.ACTION_MOVE, x.toFloat(), 150f, 0); SystemClock.sleep(16)
            }
            surface.dispatchTouch(MotionEvent.ACTION_UP, 240f, 150f, 0)
            waitUntil("compiled(inspection+spacer) drag fired") { GestureProbe.drags.get() > 0 }
            log("COMPILED-INSPECTION-SPACER: drags=${GestureProbe.drags.get()}")
            assertTrue("compiled gesture under inspection+Spacer didn't fire — layout/inspection is the cause", GestureProbe.drags.get() > 0)
        } finally {
            runCatching { surface.close() }
        }
    }

    private fun waitUntil(what: String, deadlineMs: Long = 8_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(20)
        }
        log("timed out waiting for: $what")
    }
}
