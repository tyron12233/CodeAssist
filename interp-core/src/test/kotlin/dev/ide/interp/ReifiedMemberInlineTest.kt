package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A reified `inline fun <reified T>` MEMBER threads its call-site type argument into the body, so `x is T`
 * resolves to the concrete type. The top-level / extension dispatch already did this; the member dispatch
 * (`callMethod`) did not, so `x is T` in a member was erased.
 */
class ReifiedMemberInlineTest {

    @Test
    fun reifiedMemberInlineFunctionThreadsTypeArgument() {
        val code = """
            class Checker {
                inline fun <reified T> handles(x: Any?): Boolean = x is T
            }
            fun main(): Boolean {
                val c = Checker()
                return c.handles<String>("hi") && !c.handles<Int>("hi")
            }
        """.trimIndent()
        assertEquals(true, runProgram(code, "main/0", emptyList()))
    }
}
