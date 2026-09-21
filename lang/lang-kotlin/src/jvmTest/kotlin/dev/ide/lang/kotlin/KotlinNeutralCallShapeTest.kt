package dev.ide.lang.kotlin

import dev.ide.lang.dom.KotlinNodeKinds
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.argumentNodes
import dev.ide.lang.dom.calleeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin half of the neutral call contract. `JavaDomShapeTest` asserts the same facts against the
 * Java backend; the two together are what makes a cross-language pattern honest, and either one alone
 * only describes a backend.
 *
 * The facts, in the order they matter:
 *  - a call's parens are a [NodeKind.ARGUMENT_LIST] on both sides,
 *  - a string is a [NodeKind.STRING_LITERAL] on both sides,
 *  - `argumentNodes()` and `calleeName()` answer the same thing for the same code,
 * even though the trees UNDER the argument list still differ (Kotlin wraps an argument, Java does not).
 * That last difference is deliberate, because Kotlin's named arguments have to live somewhere, and it is
 * exactly what the two helpers exist to absorb.
 */
class KotlinNeutralCallShapeTest {

    @Test
    fun `a call carries its arguments in an ARGUMENT_LIST child`() {
        val pf = parse("fun f() { rgb(255, 128, 0) }")
        val call = assertNotNull(pf.flatten().firstOrNull { it.kind == NodeKind.METHOD_CALL })

        val lists = call.children.filter { it.kind == NodeKind.ARGUMENT_LIST }
        assertEquals(1, lists.size, "a call has exactly one argument list; got ${call.children.map { it.kind.id }}")
        assertEquals("rgb", call.calleeName())

        // Kotlin DOES wrap each argument, unlike Java. Both wrappers and bare values read the same way.
        assertTrue(
            lists.single().children.all { it.kind == NodeKind.ARGUMENT },
            "Kotlin wraps arguments: ${lists.single().children.map { it.kind.id }}",
        )
        val args = call.argumentNodes()
        assertEquals(listOf("255", "128", "0"), args.map { it.text().toString() })
        assertTrue(args.all { it.kind == NodeKind.LITERAL }, "got ${args.map { it.kind.id }}")
    }

    @Test
    fun `a named argument yields its value, not its name half`() {
        val pf = parse("fun f() { rgb(red = 255, green = 128) }")
        val call = assertNotNull(pf.flatten().firstOrNull { it.kind == NodeKind.METHOD_CALL })

        assertEquals(listOf("255", "128"), call.argumentNodes().map { it.text().toString() })

        // The name is still reachable, on the ARGUMENT wrapper, for a caller that wants it.
        val names = call.children.single { it.kind == NodeKind.ARGUMENT_LIST }
            .children.mapNotNull { arg ->
                arg.children.firstOrNull { it.kind == KotlinNodeKinds.ARGUMENT_NAME }?.text()?.toString()
            }
        assertEquals(listOf("red", "green"), names)
    }

    @Test
    fun `a trailing lambda is not a parenthesised argument`() {
        val pf = parse("fun f() { run(1) { 2 } }")
        val call = assertNotNull(pf.flatten().firstOrNull { it.kind == NodeKind.METHOD_CALL })
        assertEquals(
            listOf("1"),
            call.argumentNodes().map { it.text().toString() },
            "`f(1) { … }` has ONE parenthesised argument; the lambda is a kt.lambda_argument sibling",
        )
        assertTrue(call.children.any { it.kind == KotlinNodeKinds.LAMBDA_ARGUMENT }, "the lambda is still there")
    }

    @Test
    fun `a string is STRING_LITERAL and other constants stay LITERAL`() {
        val pf = parse(
            """
            fun f() {
                val s = "hi"
                val r = ""${'"'}raw${'"'}""
                val n = 7
                val c = 'x'
                val b = true
            }
            """.trimIndent()
        )
        val all = pf.flatten()
        val strings = all.filter { it.kind == NodeKind.STRING_LITERAL }.map { it.text().toString() }
        assertEquals(2, strings.size, "both the plain and the raw string are STRING_LITERAL; got $strings")

        val others = all.filter { it.kind == NodeKind.LITERAL }.map { it.text().toString() }
        assertTrue("7" in others && "'x'" in others && "true" in others, "got $others")
        assertTrue(others.none { it.startsWith("\"") }, "no string may remain a plain LITERAL; got $others")
    }

    @Test
    fun `a supertype call is a CONSTRUCTOR_CALL, like Java's new`() {
        val pf = parse("open class Base(x: Int)\nclass Sub : Base(1)")
        val ctor = assertNotNull(
            pf.flatten().firstOrNull { it.kind == NodeKind.CONSTRUCTOR_CALL },
            "`: Base(1)` is the constructor call Kotlin has; a bare `Base(1)` is a METHOD_CALL",
        )
        assertEquals("Base", ctor.calleeName())
        assertEquals(listOf("1"), ctor.argumentNodes().map { it.text().toString() })
    }

    @Test
    fun `calleeName answers the same for a qualified call as Java does`() {
        // Kotlin nests the qualifier ABOVE the call, Java puts it on the callee node. Same answer.
        val pf = parse("fun f() { java.lang.Math.max(1, 2) }")
        val call = assertNotNull(pf.flatten().firstOrNull { it.kind == NodeKind.METHOD_CALL })
        assertEquals("max", call.calleeName())
    }

    @Test
    fun `an empty argument list is still a list with no arguments`() {
        val pf = parse("fun f() { g() }")
        val call = assertNotNull(pf.flatten().firstOrNull { it.kind == NodeKind.METHOD_CALL })
        assertTrue(call.children.any { it.kind == NodeKind.ARGUMENT_LIST }, "`g()` still has the `()` node")
        assertEquals(emptyList(), call.argumentNodes())
    }
}
