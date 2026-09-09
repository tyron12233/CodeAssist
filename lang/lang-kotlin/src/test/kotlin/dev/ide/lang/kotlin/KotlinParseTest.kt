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
