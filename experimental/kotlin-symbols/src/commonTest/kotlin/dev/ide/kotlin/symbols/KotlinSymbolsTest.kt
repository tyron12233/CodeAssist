package dev.ide.kotlin.symbols

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `@Metadata` path, running where it has to run.
 *
 * `KotlinSymbolsPortTest` proves this produces the same symbols as kotlin-metadata-jvm, and it can only do
 * that on the JVM. This is the other half: the same class file, decoded on every platform the module builds
 * for, asserting the Kotlin view that bytecode alone cannot give.
 */
class KotlinSymbolsTest {

    @Test
    fun theKotlinViewIsRecoveredOnEveryPlatform() {
        val decoded = assertNotNull(KotlinSymbols.decode(FUNCTION2), "decoding Function2.class")

        assertEquals("kotlin.jvm.functions.Function2", decoded.classFqn)
        assertTrue(decoded.isInterface, "Function2 is an interface")
        assertTrue(!decoded.isObject && !decoded.isFinalClass)

        assertEquals(listOf("P1", "P2", "R"), decoded.typeParameters)
        // Declaration-site variance, which the bytecode does not record at all: `Function2<in P1, in P2, out R>`.
        assertEquals(listOf("in", "in", "out"), decoded.typeParameterVariances)
        assertEquals(listOf("kotlin.Function<R>"), decoded.supertypes.map { it.render() })

        val invoke = assertNotNull(
            decoded.ownMembers.firstOrNull { it.name == "invoke" },
            "invoke",
        )
        assertEquals(SymbolKind.METHOD, invoke.kind)
        // The Kotlin signature, not the erased one: type parameters by name, and a real return type.
        assertEquals("(p1: P1, p2: P2): R", invoke.signature)
        assertEquals("R", invoke.type?.render())
        assertEquals(listOf("P1", "P2"), invoke.paramTypes.map { it?.render() })
        assertEquals(listOf("p1", "p2"), invoke.paramNames, "metadata carries names bytecode does not")
        assertEquals(listOf(false, false), invoke.paramHasDefault)
        assertTrue(Modifier.ABSTRACT in invoke.modifiers)
        assertTrue(!invoke.isExtension && !invoke.isSuspend && !invoke.isInline && !invoke.isInfix)
        assertEquals("kotlin.jvm.functions.Function2", invoke.declaringClassFqn)
        assertEquals("Function2", invoke.owner?.name)
        assertTrue(decoded.extensions.isEmpty() && decoded.topLevel.isEmpty())
    }

    @Test
    fun aFunctionTypeRendersTheWayKotlinSpellsIt() {
        // Not a decoder test: the display form is what a completion list shows, and `Function2<A, B, R>` is
        // not how anyone writes it.
        assertEquals("(A, B) -> R", TypeRendering.render("kotlin.Function2", listOf("A", "B", "R"), false))
        assertEquals("A.(B) -> R", TypeRendering.render("kotlin.Function2", listOf("A", "B", "R"), false, isExtensionFunctionType = true))
        assertEquals("suspend (A) -> R", TypeRendering.render("kotlin.SuspendFunction1", listOf("A", "R"), false))
        // A function type needs parentheses before the question mark, or `(A) -> R?` says something else.
        assertEquals("((A) -> R)?", TypeRendering.render("kotlin.Function1", listOf("A", "R"), true))
        assertEquals("List<String>?", TypeRendering.render("kotlin.collections.List", listOf("String"), true))
        assertEquals("T?", TypeRendering.render("T", emptyList(), true, isTypeParameter = true))
    }
}
