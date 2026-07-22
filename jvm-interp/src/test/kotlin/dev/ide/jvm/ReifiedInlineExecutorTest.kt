package dev.ide.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the general reified-inline mechanism directly at the VM level: a Kotlin library `inline fun
 * <reified R>` (which throws when called reflectively) is run INTERPRETED, with the `reifiedOperationMarker`
 * substituting the concrete type into the body's `instanceof`. `filterIsInstance` is the canonical case; it
 * exercises interpreting a stdlib facade body (iterate, `instanceof R`, build a list) against real objects.
 */
class ReifiedInlineExecutorTest {

    private val exec = ReifiedInlineExecutor()

    @Test fun filterIsInstanceKeepsOnlyTheReifiedType() {
        val mixed = listOf(1, "a", 2, "b", 3)
        val box = exec.invoke(
            ownerFqn = "kotlin.collections.CollectionsKt",
            name = "filterIsInstance",
            reifiedTypes = mapOf("R" to "java/lang/String"),
            args = listOf(mixed),
        )
        assertTrue(box != null, "the executor should run filterIsInstance")
        assertEquals(listOf("a", "b"), box.value)
    }

    @Test fun filterIsInstanceForIntUsesTheRuntimeBoxedType() {
        val mixed = listOf(1, "a", 2, "b")
        val box = exec.invoke(
            "kotlin.collections.CollectionsKt", "filterIsInstance",
            mapOf("R" to "java/lang/Integer"), listOf(mixed),
        )
        assertEquals(listOf(1, 2), box?.value)
    }

    @Test fun filterIsInstanceToFillsTheDestination() {
        val mixed = listOf(1, "a", 2, "b")
        val dest = ArrayList<String>()
        val box = exec.invoke(
            "kotlin.collections.CollectionsKt", "filterIsInstanceTo",
            mapOf("R" to "java/lang/String"), listOf(mixed, dest),
        )
        assertTrue(box != null, "the executor should run filterIsInstanceTo")
        assertEquals(listOf("a", "b"), dest)
    }

    @Test fun unknownFunctionDeclines() {
        assertNull(exec.invoke("kotlin.collections.CollectionsKt", "noSuchReifiedFn", mapOf("R" to "java/lang/String"), listOf(listOf(1))))
    }

    @Test fun javaClassOperationKind() {
        val box = exec.invoke(
            "dev.ide.jvm.kfixtures.KFxKt", "classOf",
            mapOf("T" to "java/lang/String"), emptyList(),
        )
        assertEquals(String::class.java, box?.value, "T::class.java resolves to the concrete type's class")
    }

    @Test fun asCastOperationKind() {
        val box = exec.invoke(
            "dev.ide.jvm.kfixtures.KFxKt", "castTo",
            mapOf("T" to "java/lang/String"), listOf<Any?>("hello"),
        )
        assertEquals("hello", box?.value, "as T checkcast passes a matching value through")
    }
}
