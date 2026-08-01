package dev.ide.interp.compose

import dev.ide.interp.InterpreterException

/**
 * Drives the Compose caller-side group / restart / skip protocol on a composer — the ops the interpreter emits in
 * the Compose compiler plugin's place (open/close groups, restart groups, the `$changed` skip fast path, scope
 * registration for recomposition, and mid-composition unwind). Two impls select by the composer's nature:
 *
 * - [ReflectiveComposerOps] drives a REAL host `androidx.compose.runtime.Composer` by reflection — the
 *   bridged-composer path (close-version projects, the existing preview). It delegates to [ComposableAbi], so that
 *   path is byte-for-byte unchanged.
 * - [VmComposerOps] drives an INTERPRETED composer (a `VmObject` from the project's own runtime on the bytecode
 *   VM) by invoking its methods THROUGH the VM — the interpreted-runtime path (the too-new version ceiling,
 *   milestone A). `composer.javaClass` on a `VmObject` is the VM wrapper, not `ComposerImpl`, so host reflection
 *   can't reach the composer's methods; the VM dispatches them by name.
 *
 * [ComposeDispatcher.opsFor] picks per composer, so the same interpreter drives either a host or an interpreted
 * composer with no other change on the source-composable path.
 */
interface ComposerOps {
    fun startGroup(composer: Any, key: Int)
    fun endGroup(composer: Any)
    fun startRestartGroup(composer: Any, key: Int): Any
    fun endRestartGroup(composer: Any): Any?
    fun currentMarker(composer: Any): Int
    fun endToMarker(composer: Any, marker: Int)
    fun argsChanged(composer: Any, args: List<Any?>): Boolean
    fun isSkipping(composer: Any): Boolean
    fun skipToGroupEnd(composer: Any)
    fun updateScope(scope: Any?, recompose: (Any) -> Unit)
}

/** The bridged-composer path: reflect on a real host `Composer` (delegates to the existing [ComposableAbi]). */
object ReflectiveComposerOps : ComposerOps {
    override fun startGroup(composer: Any, key: Int) = ComposableAbi.startGroup(composer, key)
    override fun endGroup(composer: Any) = ComposableAbi.endGroup(composer)
    override fun startRestartGroup(composer: Any, key: Int): Any = ComposableAbi.startRestartGroup(composer, key)
    override fun endRestartGroup(composer: Any): Any? = ComposableAbi.endRestartGroup(composer)
    override fun currentMarker(composer: Any): Int = ComposableAbi.currentMarker(composer)
    override fun endToMarker(composer: Any, marker: Int) = ComposableAbi.endToMarker(composer, marker)
    override fun argsChanged(composer: Any, args: List<Any?>): Boolean = ComposableAbi.argsChanged(composer, args)
    override fun isSkipping(composer: Any): Boolean = ComposableAbi.isSkipping(composer)
    override fun skipToGroupEnd(composer: Any) = ComposableAbi.skipToGroupEnd(composer)
    override fun updateScope(scope: Any?, recompose: (Any) -> Unit) = ComposableAbi.updateScope(scope, recompose)
}

/**
 * The interpreted-runtime path: drive a `VmObject` composer's methods through the bytecode VM (via
 * [VmLibraryExecutor.invokeInstance]/[VmLibraryExecutor.propertyOrNull]) instead of host reflection.
 *
 * Group names are version-tolerant (`startReplaceGroup`/`startReplaceableGroup`, `endReplaceGroup`/
 * `endReplaceableGroup`) since the interpreted composer is the PROJECT's version, which can be newer or older than
 * the bundled one. The composer methods this calls are the same ones the reflective path invokes; the difference
 * is only the dispatch mechanism.
 */
class VmComposerOps(private val vm: VmLibraryExecutor) : ComposerOps {

    override fun startGroup(composer: Any, key: Int) {
        invokeAny(composer, START_REPLACE, listOf(key))
    }

    override fun endGroup(composer: Any) {
        invokeAny(composer, END_REPLACE, emptyList())
    }

    override fun startRestartGroup(composer: Any, key: Int): Any =
        vm.invokeInstance(composer, "startRestartGroup", listOf(key), 0)
            ?: throw InterpreterException("interpreted startRestartGroup returned null")

    override fun endRestartGroup(composer: Any): Any? =
        vm.invokeInstance(composer, "endRestartGroup", emptyList(), 0)

    override fun currentMarker(composer: Any): Int =
        (vm.propertyOrNull(composer, "currentMarker")?.value as? Int) ?: 0

    override fun endToMarker(composer: Any, marker: Int) {
        vm.invokeInstance(composer, "endToMarker", listOf(marker), 0)
    }

    override fun argsChanged(composer: Any, args: List<Any?>): Boolean {
        // Offer every arg to `changed` unconditionally (each call advances one slot, so skipping one would desync
        // the slot table on the next pass); report whether any differs. Whichever `changed` overload the VM binds
        // records + compares one slot, so the fast path is correct regardless of the arg's runtime type.
        if (args.isEmpty()) return false
        var dirty = false
        for (a in args) dirty = (vm.invokeInstance(composer, "changed", listOf(a), 0) as? Boolean == true) || dirty
        return dirty
    }

    override fun isSkipping(composer: Any): Boolean =
        vm.propertyOrNull(composer, "skipping")?.value as? Boolean ?: false

    override fun skipToGroupEnd(composer: Any) {
        vm.invokeInstance(composer, "skipToGroupEnd", emptyList(), 0)
    }

    override fun updateScope(scope: Any?, recompose: (Any) -> Unit) {
        if (scope == null) return
        // The interpreted runtime expects a `(Composer, Int) -> Unit`; a host Kotlin lambda is a Function2 the VM
        // stores and (on recomposition) invokes back into host code — the same shape ComposableAbi.updateScope uses.
        val block: Function2<Any?, Any?, Unit> = { composer, _ -> composer?.let(recompose) }
        vm.invokeInstance(scope, "updateScope", listOf(block), 0)
    }

    /** Invoke the first of [names] the interpreted composer actually declares (version-tolerant group naming). */
    private fun invokeAny(composer: Any, names: List<String>, args: List<Any?>): Any? {
        var last: Throwable? = null
        for (n in names) {
            try {
                return vm.invokeInstance(composer, n, args, 0)
            } catch (e: InterpreterException) {
                last = e
            }
        }
        throw last ?: InterpreterException("no composer op ${names.firstOrNull()}")
    }

    private companion object {
        val START_REPLACE = listOf("startReplaceGroup", "startReplaceableGroup")
        val END_REPLACE = listOf("endReplaceGroup", "endReplaceableGroup")
    }
}
