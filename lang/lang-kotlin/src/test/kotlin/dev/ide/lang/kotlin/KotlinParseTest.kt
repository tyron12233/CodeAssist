package dev.ide.lang.kotlin

import dev.ide.lang.dom.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Navigates the neutral DOM of a real `.kt` file, and stays error-tolerant on broken input. Also a
 * measurement (cold-start + warm per-file parse), printed so the on-device numbers can be compared
 * against the desktop baseline.
 */
class KotlinParseTest {

    @Test
    fun parsesRealFileToNeutralDom() {
        val src = """
            package demo

            import kotlin.math.max

            class Greeter(val who: String) {
                val greeting: String = "Hello"

                fun greet(name: String): String {
                    val target = name
                    return target
                }
            }

            fun top(): Int = max(1, 2)
        """.trimIndent()

        val pf = parse(src)
        assertEquals(NodeKind.COMPILATION_UNIT, pf.kind)
        assertEquals(src.length, pf.range.end, "the tree must cover the whole file")

        val all = pf.flatten()
        assertTrue(all.any { it.kind == NodeKind.PACKAGE_DECL }, "package decl present")
        assertTrue(all.any { it.kind == NodeKind.IMPORT_DECL }, "import decl present")
        assertTrue(all.any { it.kind == NodeKind.CLASS_DECL }, "class decl present")
        assertTrue(all.count { it.kind == NodeKind.METHOD_DECL } >= 2, "greet + top are method decls")
        assertTrue(all.any { it.kind == KotlinNodeKinds.PROPERTY }, "member property 'greeting' is a kt.property")
        assertTrue(all.any { it.kind == NodeKind.LOCAL_VAR }, "local 'val target' is a local_var")
        assertTrue(all.any { it.kind == NodeKind.PARAMETER }, "params 'who'/'name' are parameters")
        assertTrue(pf.diagnostics.isEmpty(), "valid source has no syntax diagnostics: ${pf.diagnostics}")
    }

    @Test
    fun errorTolerantOnBrokenInput() {
        // The buffer the editor holds mid-keystroke: unbalanced parens/braces, a dangling '='.
        val broken = "fun broken( {\n    val x = \n"
        val pf = parse(broken)
        // Never throws; the tree still spans the whole file; broken regions surface as diagnostics.
        assertEquals(broken.length, pf.range.end)
        assertTrue(pf.diagnostics.isNotEmpty(), "broken input should yield syntax diagnostics")
        assertNotNull(pf.nodeAt(broken.length)) // caret at EOF resolves to a node, not a crash
    }

    @Test
    fun nodeAtLandsOnMemberAccessSelector() {
        val src = "fun f(s: String) { val r = s.length }"
        val pf = parse(src)
        val off = src.indexOf(".length") + 2 // inside "length"
        val node = pf.nodeAt(off)
        // 'length' is the selector of a dot-qualified expr; the leaf climbs to NAME_REF, parent MEMBER_ACCESS.
        assertTrue(
            node.kind == NodeKind.NAME_REF || node.kind == NodeKind.MEMBER_ACCESS,
            "expected name_ref/member_access at the selector, got ${node.kind.id}",
        )
        val hasMemberAccess = generateSequence(node) { it.parent }.any { it.kind == NodeKind.MEMBER_ACCESS }
        assertTrue(hasMemberAccess, "selector should sit under a member_access")
    }

    @Test
    fun interpolationIsItsOwnKindApartFromLiteralPieces() {
        val src = """
            fun f(x: Int) {
                val plain = "no dollars here"
                val simple = "has ${'$'}x inside"
                val braced = "has ${'$'}{x + 1} inside"
                val raw = ""${'"'}
                    line one
                    line two
                ""${'"'}
            }
        """.trimIndent()

        val pf = parse(src)
        val templates = pf.flatten().filter { it.kind == KotlinNodeKinds.STRING_TEMPLATE }
        assertEquals(4, templates.size, "every string literal is a kt.string_template")

        fun interpolated(n: dev.ide.lang.dom.DomNode) =
            n.children.any { it.kind == KotlinNodeKinds.STRING_TEMPLATE_INTERPOLATION }

        val (plain, simple, braced, raw) = templates
        assertTrue(!interpolated(plain), "a plain string has no interpolation entry")
        assertTrue(interpolated(simple), "`${'$'}x` is an interpolation entry")
        assertTrue(interpolated(braced), "`${'$'}{…}` is an interpolation entry")
        // The multiline case: several literal pieces (one per line), none of them an interpolation.
        assertTrue(raw.children.size > 1, "a raw string is cut into one literal entry per line")
        assertTrue(!interpolated(raw), "a raw string without `${'$'}` must not read as interpolated")
        assertTrue(
            raw.children.all { it.kind == KotlinNodeKinds.STRING_TEMPLATE_ENTRY },
            "raw-string pieces are literal entries: ${raw.children.map { it.kind.id }}",
        )
        // The embedded expression hangs off the interpolation entry, not off the template.
        val simpleEntry = simple.children.first { it.kind == KotlinNodeKinds.STRING_TEMPLATE_INTERPOLATION }
        assertTrue(simpleEntry.children.any { it.kind == NodeKind.NAME_REF }, "`${'$'}x` wraps a name_ref")
    }

    @Test
    fun controlFlowAndDeclarationStructureAreNamedKinds() {
        val src = """
            /** Doc with a [List] link. */
            class C(private val n: Int) : Runnable {
                val lazy: String by kotlin.lazy { "x" }
                var p: Int = 0
                    get() = field
                init { }
                override fun run() { }
            }

            fun f(xs: List<Int>, flag: Boolean): Int {
                if (flag) { return 1 } else return 2
                xs.forEach { println(it) }
                for (x in xs) { if (x > 0) break else continue }
                while (flag) { }
                do { } while (flag)
                try { throw IllegalStateException() } catch (e: Exception) { } finally { }
                val (a, b) = 1 to 2
                return when (xs.size) { 0 -> a; in 1..2 -> b; is Int -> 3; else -> 4 }
            }
        """.trimIndent()

        val pf = parse(src)
        assertTrue(pf.diagnostics.isEmpty(), "sample should parse clean: ${pf.diagnostics}")
        val all = pf.flatten()
        val kinds = all.map { it.kind }.toSet()

        val expected = listOf(
            KotlinNodeKinds.IF, KotlinNodeKinds.FOR, KotlinNodeKinds.WHILE, KotlinNodeKinds.DO_WHILE,
            KotlinNodeKinds.RETURN, KotlinNodeKinds.THROW, KotlinNodeKinds.BREAK, KotlinNodeKinds.CONTINUE,
            KotlinNodeKinds.TRY, KotlinNodeKinds.CATCH, KotlinNodeKinds.FINALLY,
            KotlinNodeKinds.WHEN_ENTRY, KotlinNodeKinds.WHEN_CONDITION,
            KotlinNodeKinds.WHEN_CONDITION_IS, KotlinNodeKinds.WHEN_CONDITION_IN,
            KotlinNodeKinds.CONTROL_BODY, KotlinNodeKinds.CONTAINER,
            KotlinNodeKinds.CLASS_BODY, KotlinNodeKinds.MODIFIER_LIST, KotlinNodeKinds.PARAMETER_LIST,
            KotlinNodeKinds.SUPERTYPE_LIST, KotlinNodeKinds.SUPERTYPE_ENTRY,
            KotlinNodeKinds.PROPERTY_ACCESSOR, KotlinNodeKinds.PROPERTY_DELEGATE, KotlinNodeKinds.INIT,
            KotlinNodeKinds.DESTRUCTURING, KotlinNodeKinds.DESTRUCTURING_ENTRY,
            KotlinNodeKinds.USER_TYPE, KotlinNodeKinds.TYPE_ARGUMENT_LIST, KotlinNodeKinds.TYPE_PROJECTION,
            KotlinNodeKinds.ARGUMENT_LIST, KotlinNodeKinds.ARGUMENT, KotlinNodeKinds.LAMBDA_ARGUMENT,
            KotlinNodeKinds.FUNCTION_LITERAL, KotlinNodeKinds.OPERATOR,
            KotlinNodeKinds.KDOC_LINK, KotlinNodeKinds.KDOC_NAME, KotlinNodeKinds.IMPORT_LIST,
        )
        val missing = expected.filterNot { it in kinds }
        assertTrue(missing.isEmpty(), "these shapes fell through to another kind: ${missing.map { it.id }}")

        // The motivating case: an `if` branch body is distinguishable from a lambda without reading PSI.
        val branches = all.first { it.kind == KotlinNodeKinds.IF }
            .children.filter { it.kind == KotlinNodeKinds.CONTROL_BODY }
        assertEquals(2, branches.size, "`if`/`else` bodies are control bodies, the condition is a container")
        assertTrue(
            all.none { it.kind == KotlinNodeKinds.CONTROL_BODY && it.text().startsWith("{ println") },
            "a lambda body must not read as a control-structure body",
        )
    }

    @Test
    fun theCatchAllKindStaysEmptyOnOrdinarySource() {
        // Deliberately long-tail: the shapes that are rare in any one file but common across a project.
        val src = """
            @file:JvmName("X")
            @file:[Suppress("a") Suppress("b")]

            package p

            import kotlin.collections.List as L

            annotation class A(val xs: IntArray)

            @A([1, 2])
            open class Base(x: Int)

            enum class E(val n: Int) { ONE(1), TWO(2) }

            class D(y: Int, r: Runnable) : Base(y), Runnable by r {
                constructor(r: Runnable) : this(1, r)
                @get:JvmName("z") val z = 1
                fun probe(o: Any): String {
                    val cast = o as? Int
                    if (o is Int) println(this)
                    return super.toString() + cast
                }
            }

            fun <T> w(x: T?): T where T : Comparable<T> = x!!

            fun hof(f: Int.(String) -> Unit, g: (Int) -> Unit) { }

            context(s: String)
            fun ctx(): Any {
                val m = ${'$'}${'$'}"multi dollar"
                val labelled = run { outer@ for (x in 1..2) { if (x > 0) break@outer }; 0 }
                val annotated = @Suppress("x") 1
                val ref = ::ctx
                val cls = D::class
                val obj = object : Runnable { override fun run() { } }
                return listOf(m, labelled, annotated, ref, cls, obj, -1, 1.inc()!!, (1))[0]
            }

            fun guard(x: Any) = when (x) { is Int if x > 0 -> 1; else -> 0 }
        """.trimIndent()

        val pf = parse(src)
        assertTrue(pf.diagnostics.isEmpty(), "sample should parse clean: ${pf.diagnostics}")
        val all = pf.flatten()

        // Reachability: a branch shadowed by an earlier `is` check would not show up as a leftover, it
        // would quietly hand back the wrong kind. Every rare shape above has to name itself.
        val expected = listOf(
            KotlinNodeKinds.FILE_ANNOTATION_LIST, KotlinNodeKinds.ANNOTATION_GROUP,
            KotlinNodeKinds.ANNOTATION_ENTRY, KotlinNodeKinds.ANNOTATION_USE_SITE,
            KotlinNodeKinds.ANNOTATED_EXPRESSION, KotlinNodeKinds.COLLECTION_LITERAL,
            KotlinNodeKinds.IMPORT_ALIAS,
            KotlinNodeKinds.SUPERTYPE_CALL, KotlinNodeKinds.SUPERTYPE_DELEGATE,
            KotlinNodeKinds.CONSTRUCTOR_CALLEE, KotlinNodeKinds.CONSTRUCTOR_DELEGATION_CALL,
            KotlinNodeKinds.CONSTRUCTOR_DELEGATION_REF,
            KotlinNodeKinds.TYPE_PARAMETER, KotlinNodeKinds.TYPE_PARAMETER_LIST,
            KotlinNodeKinds.TYPE_CONSTRAINT, KotlinNodeKinds.TYPE_CONSTRAINT_LIST,
            KotlinNodeKinds.NULLABLE_TYPE, KotlinNodeKinds.FUNCTION_TYPE,
            KotlinNodeKinds.FUNCTION_TYPE_RECEIVER, KotlinNodeKinds.CONTEXT_RECEIVER_LIST,
            KotlinNodeKinds.STRING_INTERPOLATION_PREFIX, KotlinNodeKinds.WHEN_ENTRY_GUARD,
            KotlinNodeKinds.LABELED_EXPRESSION, KotlinNodeKinds.LABEL_REF,
            KotlinNodeKinds.CALLABLE_REFERENCE, KotlinNodeKinds.CLASS_LITERAL,
            KotlinNodeKinds.OBJECT_LITERAL, KotlinNodeKinds.THIS, KotlinNodeKinds.SUPER,
            KotlinNodeKinds.IS, KotlinNodeKinds.AS,
            KotlinNodeKinds.PREFIX, KotlinNodeKinds.POSTFIX, KotlinNodeKinds.PARENTHESIZED,
            KotlinNodeKinds.ARRAY_ACCESS,
        )
        val kinds = all.map { it.kind }.toSet()
        val missing = expected.filterNot { it in kinds }
        assertTrue(missing.isEmpty(), "these kinds never fired: ${missing.map { it.id }}")

        val leftovers = all.filter { it.kind == KotlinNodeKinds.OTHER }
        // A construct landing in `kt.element` cannot be told from any other, which is what forces its
        // consumers back onto PSI. A new one showing up here wants a kind in KotlinNodeKinds.
        assertTrue(
            leftovers.isEmpty(),
            "unclassified nodes: ${leftovers.map { it.text().toString().take(30) }}",
        )
    }

    @Test
    fun spikeColdStartAndWarmParse() {
        val sample = buildString {
            append("package demo\n\n")
            repeat(40) { i ->
                append("fun f$i(x: Int): Int {\n")
                append("    val a = x + $i\n")
                append("    val b = listOf(a, $i).map { it * 2 }\n")
                append("    return a + b.size\n")
                append("}\n\n")
            }
        }

        val cold = System.nanoTime()
        val first = parse(sample)
        val coldMs = (System.nanoTime() - cold) / 1_000_000.0
        assertTrue(first.diagnostics.isEmpty(), "sample should parse clean: ${first.diagnostics.take(3)}")

        // Warm parses (host already up).
        val iters = 20
        val warm = System.nanoTime()
        repeat(iters) { parse(sample) }
        val warmMs = (System.nanoTime() - warm) / 1_000_000.0 / iters

        println("[kotlin-spike] cold-start+first-parse=${"%.1f".format(coldMs)}ms  warm-parse=${"%.2f".format(warmMs)}ms/file (${sample.length} chars)")
        // Sanity check only. Desktop warm parse should be well under 100ms.
        assertTrue(warmMs < 250.0, "warm parse unexpectedly slow: ${warmMs}ms")
    }
}
