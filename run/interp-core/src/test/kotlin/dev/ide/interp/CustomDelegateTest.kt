package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Custom property delegates via the general `operator fun getValue`/`setValue` convention (beyond the
 * `.value` State/Lazy fast-path). The resolver detects a delegate whose type has a MEMBER getValue/setValue
 * and lowers the `by` property to a convention binding; the interpreter reads through `delegate.getValue(
 * thisRef, property)` and writes through `delegate.setValue(thisRef, property, value)`, passing a synthetic
 * `KProperty` whose `name` is the delegated property's own name. Covers local + member delegates, `val` + `var`,
 * and `property.name` use.
 */
class CustomDelegateTest {

    @Test fun localValDelegateReadsThroughGetValue() {
        val code = """
            package demo
            import kotlin.reflect.KProperty
            class Doubler { operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = 21 * 2 }
            fun f(): Int { val x by Doubler(); return x }
        """.trimIndent()
        assertEquals(42, runProgram(code, "f/0", emptyList()))
    }

    @Test fun localVarDelegateWritesThroughSetValue() {
        val code = """
            package demo
            import kotlin.reflect.KProperty
            class Store(var backing: Int) {
                operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = backing
                operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { backing = value }
            }
            fun f(): Int { var x by Store(1); x = 10; return x + 5 }
        """.trimIndent()
        assertEquals(15, runProgram(code, "f/0", emptyList()))
    }

    @Test fun delegateSeesThePropertyName() {
        val code = """
            package demo
            import kotlin.reflect.KProperty
            class Named { operator fun getValue(thisRef: Any?, property: KProperty<*>): String = property.name }
            fun f(): String { val myProperty by Named(); return myProperty }
        """.trimIndent()
        assertEquals("myProperty", runProgram(code, "f/0", emptyList()))
    }

    @Test fun augmentedAssignThroughDelegate() {
        val code = """
            package demo
            import kotlin.reflect.KProperty
            class Store(var backing: Int) {
                operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = backing
                operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { backing = value }
            }
            fun f(): Int { var x by Store(3); x += 4; return x }
        """.trimIndent()
        assertEquals(7, runProgram(code, "f/0", emptyList()))
    }

    @Test fun memberValDelegate() {
        val code = """
            package demo
            import kotlin.reflect.KProperty
            class Doubler { operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = 21 * 2 }
            class Holder { val computed by Doubler(); fun read(): Int = computed }
            fun f(): Int = Holder().read()
        """.trimIndent()
        assertEquals(42, runProgram(code, "f/0", emptyList()))
    }

    @Test fun memberVarDelegateReadModifyWrite() {
        val code = """
            package demo
            import kotlin.reflect.KProperty
            class Store(var b: Int) {
                operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = b
                operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { b = value }
            }
            class Holder { var value by Store(5); fun bump(): Int { value = value + 100; return value } }
            fun f(): Int = Holder().bump()
        """.trimIndent()
        assertEquals(105, runProgram(code, "f/0", emptyList()))
    }
}
