package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Build.VERSION.SDK_INT` shape: a static field on a NESTED class reached through its enclosing type. The
 * interpreter must read `Build.VERSION` as the nested Class (not a static field of `Build`), then read
 * `SDK_INT` as a static field off THAT class. `java.util.jar.Attributes.Name.MANIFEST_VERSION` is the JVM
 * analog on the test classpath (a `public static final Attributes.Name` on the nested `Attributes.Name`).
 * Before the fix this threw "no static member `Name` on java.util.jar.Attributes".
 */
class NestedStaticMemberTest {

    private val span = SourceSpan(0, 0)

    @Test
    fun staticFieldOnANestedClassReachedThroughItsEnclosingTypeResolves() {
        // `Attributes.Name` — the nested-class access (like `Build.VERSION`).
        val nested = RNode.PropertyGet(
            RNode.Name(Binding.ObjectRef("java.util.jar.Attributes", "Attributes"), span),
            Binding.Property("Name", "java.util.jar.Attributes", backingField = false), span,
        )
        // `Attributes.Name.MANIFEST_VERSION` — the static field off the nested class (like `.SDK_INT`).
        val field = RNode.PropertyGet(
            nested,
            Binding.Property("MANIFEST_VERSION", "java.util.jar.Attributes.Name", backingField = false), span,
        )
        val fn = ResolvedFunction("f", emptyList(), field, emptyList())
        val result = Interpreter(emptyMap()).call(fn, emptyList())
        assertEquals("Manifest-Version", result?.toString(), "the nested-class static field should resolve to its value")
    }
}
