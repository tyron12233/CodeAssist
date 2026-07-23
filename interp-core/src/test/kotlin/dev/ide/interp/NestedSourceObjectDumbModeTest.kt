package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * While the index is still cold ("dumb mode"), a nested source `object` read `State.Loading` lowers to a member
 * read `PropertyGet(ObjectRef(State), "Loading")` rather than the single `ObjectRef` the warm path produces.
 * `State` is a sealed interface with no companion, so this used to fall through to `objectInstance` and throw
 * "cannot load `State` (a project-source object…)". `sourceQualifierMember` now resolves the nested object's
 * singleton directly.
 */
class NestedSourceObjectDumbModeTest {

    @Test
    fun nestedSourceObjectReadOffASealedInterfaceResolves() {
        val (_, classes) = lowerProgramFull(
            """
            sealed interface State {
                object Loading : State
                data class Loaded(val n: Int) : State
            }
            """.trimIndent(),
        )
        // The dumb-mode lowering shape: `State.Loading` as a member read, NOT a single ObjectRef.
        val span = SourceSpan(0, 0)
        val body = RNode.PropertyGet(
            RNode.Name(Binding.ObjectRef("State", "State"), span),
            Binding.Property("Loading", "State", backingField = false), span,
        )
        val fn = ResolvedFunction("f", emptyList(), body, emptyList())
        val result = Interpreter(emptyMap(), classes = classes).call(fn, emptyList())
        assertTrue(result is SourceObject, "State.Loading must resolve to its source-object singleton, got $result")
        assertTrue((result as SourceObject).cls.simpleName == "Loading", "the singleton is State.Loading, was ${result.cls.fqn}")
    }
}
