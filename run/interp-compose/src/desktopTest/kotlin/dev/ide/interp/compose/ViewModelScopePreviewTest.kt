package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.interp.SourceObject
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Constructing an interpreted `class VM : ViewModel()` whose body reads `viewModelScope` must NOT crash the
 * preview. `viewModelScope` is a library EXTENSION property (`val ViewModel.viewModelScope`) whose real getter
 * builds a scope on `Dispatchers.Main` — unavailable in a headless preview. The Compose dispatcher intercepts
 * the read ([ComposeDispatcher.readExtensionPropertyOverride]) and hands back a plain, Main-free
 * `CoroutineScope`, so the property initializer completes. Reproduces the reported ViewModel preview failure
 * ("unresolved name `viewModelScope`" / a `Dispatchers.Main` crash) at the interpreter layer.
 */
class ViewModelScopePreviewTest {

    private val span = SourceSpan(0, 0)

    @Test
    fun viewModelScopeReadsAsAHeadlessCoroutineScope() {
        val recvSlot = SlotId(0)
        fun thisRef() = RNode.Name(Binding.Local(recvSlot, "this", mutable = false), span)
        // `val scope = viewModelScope` inside the class body: a PropertySet of a field to the extension read.
        val readViewModelScope = RNode.PropertyGet(
            thisRef(),
            Binding.Property("viewModelScope", "androidx.lifecycle.ViewModelKt", backingField = false, isExtension = true),
            span,
        )
        val vmClass = ResolvedClass(
            fqn = "demo.VM", simpleName = "VM", flavor = ClassFlavor.CLASS, isData = false,
            isSealed = false, isAbstract = false, primaryParams = emptyList(),
            initSteps = listOf(
                RNode.PropertySet(thisRef(), Binding.Property("scope", "demo.VM", backingField = false), readViewModelScope, span),
            ),
            methods = emptyMap(), receiverSlot = recvSlot,
            supertypes = listOf("androidx.lifecycle.ViewModel"), enumEntries = emptyList(), diagnostics = emptyList(),
        )
        val ctorCall = RNode.Call(
            ResolvedCallable.Source("VM", "demo.VM/0", emptyList(), isConstructor = true),
            DispatchKind.CONSTRUCTOR, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(1), source = span,
        )
        val box = ResolvedFunction("box", emptyList(), RNode.Return(ctorCall, span), emptyList())

        val result = Interpreter(mapOf("box/0" to box), ComposeDispatcher(), classes = listOf(vmClass)).call(box, emptyList())
        assertTrue(result is SourceObject, "constructing the ViewModel must yield a SourceObject; was $result")
        val scope = (result as SourceObject).fields["scope"]
        assertTrue(scope is CoroutineScope, "viewModelScope must read as a headless CoroutineScope, not crash; was $scope")
    }
}
