package dev.ide.interp.compose

import androidx.compose.runtime.mutableStateOf
import dev.ide.interp.Interpreter
import dev.ide.interp.SourceObject
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `by`-delegated CLASS MEMBER (`var count by mutableStateOf(0)`) must read and write through the REAL
 * `MutableState`, not a dead plain field — otherwise a state change (a click handler doing `count++`) never
 * reaches the snapshot system and the preview does not re-render. Here the member's hidden `count$delegate`
 * field holds a real `androidx.compose.runtime` `MutableState`; the test proves a member write reaches that
 * state's `setValue` (so `state.value` externally reflects it). That the snapshot system then recomposes on
 * such a write is proven by [RecompositionSkipTest]; together they close "state mutation re-renders".
 */
class MemberStateReactivityTest {
    private val span = SourceSpan(0, 0)

    private val modelClass = ResolvedClass(
        fqn = "demo.Model", simpleName = "Model", flavor = ClassFlavor.CLASS, isData = false,
        isSealed = false, isAbstract = false, primaryParams = emptyList(), initSteps = emptyList(),
        methods = emptyMap(), receiverSlot = SlotId(0), supertypes = emptyList(), enumEntries = emptyList(),
        diagnostics = emptyList(), delegatedProperties = mapOf("count" to "count\$delegate"),
    )

    private val paramSlot = SlotId(0)
    private fun modelRef() = RNode.Name(Binding.Param(paramSlot, "m"), span)
    private fun countBinding() = Binding.Property("count", "demo.Model", backingField = false)

    /** `fun read(m: Model): Int = m.count` */
    private fun readCount() = ResolvedFunction(
        "read", listOf(RParam(paramSlot, "m", null)),
        RNode.Return(RNode.PropertyGet(modelRef(), countBinding(), span), span), emptyList(),
    )

    /** `fun write(m: Model) { m.count = 5 }` */
    private fun writeCount() = ResolvedFunction(
        "write", listOf(RParam(paramSlot, "m", null)),
        RNode.PropertySet(modelRef(), countBinding(), RNode.Const(5, null, span), span), emptyList(),
    )

    @Test
    fun delegatedMemberReadsAndWritesThroughRealMutableState() {
        val state = mutableStateOf(0) // a REAL androidx.compose.runtime MutableState
        val model = SourceObject(modelClass, mutableMapOf<String, Any?>("count\$delegate" to state))
        val interp = Interpreter(functions = emptyMap())

        assertEquals(0, interp.call(readCount(), listOf(model)), "a member read routes through the delegate's `.value`")

        interp.call(writeCount(), listOf(model))
        // The write went through the REAL MutableState.setValue — so the state itself (which the snapshot system
        // observes to drive recomposition) reflects the new value, not a dead plain field that no one watches.
        assertEquals(5, state.value, "a member write reaches the real MutableState (snapshot-observed → recomposes)")
        assertEquals(5, interp.call(readCount(), listOf(model)), "the updated value reads back through the delegate")
    }
}
