package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the reported SheetState/MutableState preview crash, against the REAL composer: an interpreted
 * library composable produces a `SheetState`-shaped holder via `rememberSaveable(saver) { … }`
 * (`rememberLibSheet`), which the VM bridges to the real `RememberSaveableKt.rememberSaveable`.
 *
 * That facade declares two overloads with IDENTICAL erased params but different return types
 * (`(…Function0;Composer;I)Object` and `(…)MutableState`). Before the fix, `NativeBridge` resolved by params
 * only, so `Class.getDeclaredMethods()` order (unspecified, per-JVM/ART) decided which it invoked — and the
 * `MutableState` overload casts the holder to `MutableState`, throwing `LibSheet$Peer cannot be cast to
 * MutableState`. A per-JVM heisenbug, so [modalBottomSheetShapeIsSlotStableAcrossManyRenders] renders many
 * times. `NativeBridge.resolveMethod` now disambiguates by the descriptor's return type.
 */
class SheetStateSlotRepro {

    private val span = SourceSpan(0, 0)
    private val pkg = "dev.ide.interp.compose.libfixture"
    private val facade = "$pkg.LibComposableKt"
    private val self = "dev.ide.interp.compose.SheetStateSlotReproKt"

    private fun renderer(tolerateGaps: Boolean = true) = ComposePreviewRenderer(
        tolerateGaps = tolerateGaps,
        libraryExecutor = VmLibraryExecutor(
            hostLoadable = { !it.startsWith(pkg) },
            source = ClassBytesSource.fromClasspath(),
        ),
    )

    /** `fun SheetHost() { val sheet = rememberLibSheet(); val flag = remember { mutableStateOf("hi") };
     *   probe(sheet.tag, flag.value) }`. */
    private fun sheetHost(): ResolvedFunction {
        val sheetSlot = SlotId(0)
        val flagSlot = SlotId(1)

        // val sheet = rememberLibSheet()  — interpreted library composable returning a LibSheet holder.
        val rememberSheet = RNode.Call(
            ResolvedCallable.Library(
                "rememberLibSheet", facade, "rememberLibSheet", emptyList(),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(1), source = span,
        )
        val sheetVar = RNode.LocalVar(sheetSlot, "sheet", mutable = false, initializer = rememberSheet, source = span)

        // val flag = remember { mutableStateOf("hi") }  — bridged MutableState.
        val mso = RNode.Call(
            ResolvedCallable.Library(
                "mutableStateOf", "androidx.compose.runtime.SnapshotStateKt", "mutableStateOf",
                listOf(KotlinType("kotlin.Any"), KotlinType("androidx.compose.runtime.SnapshotMutationPolicy")),
                isStatic = true, isConstructor = false, isInline = false, isComposable = false,
                paramNames = listOf("value", "policy"),
            ),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Const("hi", KotlinType("kotlin.String"), span))), CallSiteKey(2), span,
        )
        val remember = RNode.Call(
            ResolvedCallable.Library(
                "remember", "androidx.compose.runtime.ComposablesKt", "remember",
                listOf(KotlinType("kotlin.Function0")), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, null,
            listOf(RArg(RNode.Lambda(emptyList(), RNode.Block(listOf(mso), true, span), emptyList(), span), trailingLambda = true)),
            CallSiteKey(3), span,
        )
        val flagVar = RNode.LocalVar(flagSlot, "flag", mutable = false, initializer = remember, source = span)

        // probe(sheet.tag, flag.value)
        val readTag = RNode.PropertyGet(
            RNode.Name(Binding.Local(sheetSlot, "sheet", false), span),
            Binding.Property("tag", "$pkg.LibSheet", backingField = false), span,
        )
        val readValue = RNode.PropertyGet(
            RNode.Name(Binding.Local(flagSlot, "flag", false), span),
            Binding.Property("value", "androidx.compose.runtime.MutableState", backingField = false), span,
        )
        val probeCall = RNode.Call(
            ResolvedCallable.Library("probe", self, "probe", listOf(KotlinType("kotlin.Int"), KotlinType("kotlin.Any")),
                isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(readTag), RArg(readValue)), CallSiteKey(4), span,
        )

        return ResolvedFunction("SheetHost", emptyList(), RNode.Block(listOf(sheetVar, flagVar, probeCall), false, span), emptyList(), returnsUnit = true)
    }

    @Test
    fun interpretedSheetHolderDoesNotLeakIntoTheSiblingMutableStateSlot() {
        probed = null
        var failure: Throwable? = null
        val renderer = renderer()
        composeOnce { renderer.Render(sheetHost(), emptyMap(), emptyList(), emptyList(), onError = {}, onPartialError = { it?.let { t -> failure = t } }) }

        if (failure != null) throw AssertionError("SheetState-shaped holder must not crash the sibling remember", failure)
        assertEquals(7 to "hi", probed, "the LibSheet.tag and the MutableState.value must each read from their OWN slot")
    }

    /** `fun ModalHost() { LibModalSheet(sheetLog) { probeStr("content") } }` — the exact ModalBottomSheet shape:
     *  the interpreted composable is called with its side-effectful `sheet` default OMITTED and a trailing
     *  content lambda, so its body's slot layout hinges on the `$default` mask the caller computes. */
    private fun modalHost(): ResolvedFunction {
        val content = RNode.Lambda(
            emptyList(),
            RNode.Block(listOf(probeStrCall("content")), false, span),
            emptyList(), span,
        )
        val call = RNode.Call(
            ResolvedCallable.Library(
                "LibModalSheet", facade, "LibModalSheet",
                listOf(KotlinType("kotlin.collections.MutableList"), KotlinType("$pkg.LibSheet"), KotlinType("kotlin.Function0")),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                paramNames = listOf("log", "sheet", "content"),
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Local(SlotId(0), "log", false), span)), RArg(content, trailingLambda = true)),
            callSiteKey = CallSiteKey(9), source = span,
        )
        // The `log` list is supplied as a preview arg (Render args) bound to the entry's single parameter.
        return ResolvedFunction(
            "ModalHost",
            listOf(dev.ide.lang.kotlin.interp.RParam(SlotId(0), "log", KotlinType("kotlin.collections.MutableList"))),
            RNode.Block(listOf(call), false, span), emptyList(), returnsUnit = true,
        )
    }

    private fun probeStrCall(s: String) = RNode.Call(
        ResolvedCallable.Library("probeStr", self, "probeStr", listOf(KotlinType("kotlin.String")),
            isStatic = true, isConstructor = false, isInline = false),
        DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Const(s, KotlinType("kotlin.String"), span))), CallSiteKey(10), span,
    )

    @Test
    fun modalBottomSheetShapeWithOmittedStateDefaultDoesNotDesyncSlots() {
        sheetLog.clear(); probedStr = null
        var failure: Throwable? = null
        val renderer = renderer(tolerateGaps = false)
        composeOnce {
            renderer.Render(modalHost(), emptyMap(), emptyList(), listOf(sheetLog), onError = { failure = it }, onPartialError = { it?.let { t -> failure = failure ?: t } })
        }
        if (failure != null) throw AssertionError("ModalBottomSheet-shaped call must not desync the body's slots (log=$sheetLog probed=$probedStr)", failure)
        assertEquals(listOf("sheet=7 flag=open"), sheetLog, "the defaulted sheet + internal remember must read their OWN slots")
        assertEquals("content", probedStr, "the trailing content lambda must compose")
    }

    /** The root defect (`NativeBridge` picking `rememberSaveable(...): MutableState` over `(...): Object` by the
     *  non-deterministic `Class.getDeclaredMethods()` order) made the crash a per-JVM heisenbug, so render the
     *  ModalBottomSheet shape many times and require EVERY pass to read its own slots. */
    @Test
    fun modalBottomSheetShapeIsSlotStableAcrossManyRenders() {
        val failures = ArrayList<Throwable>()
        repeat(200) {
            sheetLog.clear(); probedStr = null
            var failure: Throwable? = null
            val renderer = renderer(tolerateGaps = false)
            composeOnce {
                renderer.Render(modalHost(), emptyMap(), emptyList(), listOf(sheetLog), onError = { failure = it }, onPartialError = { t -> t?.let { failure = failure ?: it } })
            }
            if (failure != null) failures.add(failure!!)
            else if (sheetLog != listOf("sheet=7 flag=open")) failures.add(AssertionError("wrong slots: $sheetLog"))
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size}/200 renders desynced; first:", failures.first())
    }

    // --- headless composition harness (no UI) ---
    private val recomposers = ArrayList<Recomposer>()
    @AfterTest fun tearDown() = recomposers.forEach { it.cancel() }

    private fun composeOnce(content: @androidx.compose.runtime.Composable () -> Unit) {
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(UnitApplier, recomposer)
        composition.setContent(content)
        composition.dispose()
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}

@Volatile var probed: Pair<Int, Any?>? = null
fun probe(tag: Int, value: Any?) { probed = tag to value }

val sheetLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
@Volatile var probedStr: String? = null
fun probeStr(s: String) { probedStr = s }
