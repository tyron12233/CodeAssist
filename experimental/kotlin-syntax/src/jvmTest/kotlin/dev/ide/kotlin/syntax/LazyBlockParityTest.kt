package dev.ide.kotlin.syntax

import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A lazily parsed file with every collapsed body parsed on its own and put back must be the tree a full parse
 * builds. The incremental editor parse rests on exactly that: it parses the file lazily and expands a body
 * only when something looks inside it, reusing an unchanged body's expansion across keystrokes. Checked over
 * the compiler's own parser corpus (every error-recovery case it pins) and this repository's sources.
 */
class LazyBlockParityTest {

    @Test
    fun expandedLazyBodiesMatchTheFullParse() {
        val root = File(System.getProperty("kotlinSyntax.corpusRoot")!!)
        val corpus = File(root, "experimental/kotlin-syntax/testData/kotlin-psi").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
        val repo = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", ".gradle", "node_modules", ".claude", "testData") && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
        val files = (corpus + repo).sortedBy { it.path }.toList()
        assertTrue(files.size > 1000, "expected both corpora, found ${files.size}")

        val diverged = ArrayList<String>()
        var blocks = 0
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val isScript = file.extension == "kts"
            val fullTree = KotlinSyntax.parse(text, isScript)
            val full = StringBuilder().also { appendRendered(fullTree, fullTree.getRoot(), it) }.toString()
            val lazy = KotlinSyntax.parse(text, isScript, lazy = true)
            val out = StringBuilder()
            val counter = IntArray(1)
            stitched(lazy, lazy.getRoot(), out, counter)
            blocks += counter[0]
            if (out.toString() != full) diverged += file.path
        }
        println("lazy-block parity: ${files.size} files, $blocks bodies expanded, ${diverged.size} diverged")
        diverged.take(20).forEach { println("  diverged: $it") }
        assertTrue(diverged.isEmpty(), "${diverged.size} files diverge between the full parse and the expanded lazy one")
    }

    @Test
    fun expandedLazyBodiesMatchTheFullParseOnHalfTypedCode() {
        // An editor parses code that is broken by definition: a brace just opened, a quote not yet closed.
        val root = File(System.getProperty("kotlinSyntax.corpusRoot")!!)
        val files = File(root, "lang/lang-kotlin/src/commonMain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.take(120).toList()
        val random = java.util.Random(7)
        val inserts = listOf("{", "}", "(", ")", "\"", "{ x ->", "val ", "fun f() {", "->", "/*")
        val diverged = ArrayList<String>()
        var cases = 0
        for (file in files) {
            val base = file.readText().replace("\r\n", "\n")
            if (base.isEmpty()) continue
            repeat(12) {
                val at = random.nextInt(base.length)
                val text = if (random.nextBoolean()) {
                    base.substring(0, at) + inserts[random.nextInt(inserts.size)] + base.substring(at)
                } else {
                    base.removeRange(at, (at + 1 + random.nextInt(12)).coerceAtMost(base.length))
                }
                cases++
                val fullTree = KotlinSyntax.parse(text)
                val full = StringBuilder().also { appendRendered(fullTree, fullTree.getRoot(), it) }.toString()
                val lazy = KotlinSyntax.parse(text, lazy = true)
                val out = StringBuilder()
                stitched(lazy, lazy.getRoot(), out, IntArray(1))
                if (out.toString() != full) diverged += "${file.name}@$at"
                compare(KotlinSyntax.parseFile(text), KotlinSyntax.parseFileLazily(text, dev.ide.kotlin.syntax.psi.LazyBodyParser.UNCACHED))
                    ?.let { diverged += "${file.name}@$at facade: $it" }
            }
        }
        println("lazy-block parity (edited): $cases cases, ${diverged.size} diverged: ${diverged.take(10)}")
        assertTrue(diverged.isEmpty(), "${diverged.size} edited inputs diverge: ${diverged.take(10)}")
    }

    @Test
    fun theLazyFacadeReadsLikeTheFullOne() {
        // The same property one level up, where the editor reads it: every element of a lazily parsed file,
        // walked through the facade, has the type and range of the full parse's element, and names the
        // element that listed it as its parent.
        val root = File(System.getProperty("kotlinSyntax.corpusRoot")!!)
        val files = (File(root, "experimental/kotlin-syntax/testData/kotlin-psi").walkTopDown() +
            File(root, "lang/lang-kotlin/src/commonMain").walkTopDown())
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }.sortedBy { it.path }.toList()
        val diverged = ArrayList<String>()
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val isScript = file.extension == "kts"
            val full = KotlinSyntax.parseFile(text, isScript)
            val lazy = KotlinSyntax.parseFileLazily(text, dev.ide.kotlin.syntax.psi.LazyBodyParser.UNCACHED, isScript)
            val problem = compare(full, lazy)
            if (problem != null) diverged += "${file.name}: $problem"
        }
        println("lazy facade parity: ${files.size} files, ${diverged.size} diverged ${diverged.take(5)}")
        assertTrue(diverged.isEmpty(), diverged.take(10).joinToString("\n"))
    }

    private fun compare(a: dev.ide.kotlin.syntax.psi.KtElement, b: dev.ide.kotlin.syntax.psi.KtElement): String? {
        if (a.elementType != b.elementType || a.textRange != b.textRange) return "${a.elementType}${a.textRange} vs ${b.elementType}${b.textRange}"
        if (a::class != b::class) return "class ${a::class.simpleName} vs ${b::class.simpleName} at ${a.textRange}"
        val ac = a.childrenWithTrivia
        val bc = b.childrenWithTrivia
        if (ac.size != bc.size) return "children of ${a.elementType}${a.textRange}: ${ac.map { it.elementType.toString() + it.textRange }} vs ${bc.map { it.elementType.toString() + it.textRange }}"
        for (i in ac.indices) {
            if (bc[i].parent !== b) return "parent of ${bc[i].elementType}${bc[i].textRange}"
            compare(ac[i], bc[i])?.let { return it }
        }
        return null
    }

    /** [render], with each collapsed body replaced by its own parse. */
    private fun stitched(tree: LightSyntaxTree, node: LightNode, out: StringBuilder, counter: IntArray) {
        val type = tree.getType(node)
        if (tree.isToken(node)) {
            if (type in org.jetbrains.kotlin.kmp.lexer.KtTokens.WHITESPACES || type in org.jetbrains.kotlin.kmp.lexer.KtTokens.COMMENTS) return
            out.append(type.toString()); return
        }
        val children = tree.getChildren(node)
        if (collapsedBlock(tree, node) || collapsedLambda(tree, node)) {
            counter[0]++
            val text = tree.getText(node)
            val sub = if (type == KtNodeTypes.BLOCK) KotlinSyntax.parseBlock(text) else KotlinSyntax.parseLambda(text)
            val subRoot = findType(sub, sub.getRoot(), type) ?: sub.getRoot()
            appendRendered(sub, subRoot, out)
            return
        }
        out.append('(').append(type.toString())
        for (c in children) {
            val before = out.length
            out.append(' ')
            val mark = out.length
            stitched(tree, c, out, counter)
            if (out.length == mark) out.setLength(before)
        }
        out.append(')')
    }

    private fun findType(tree: LightSyntaxTree, node: LightNode, type: Any): LightNode? {
        if (tree.getType(node) == type) return node
        for (c in tree.getChildren(node)) if (!tree.isToken(c)) findType(tree, c, type)?.let { return it }
        return null
    }

    private fun hasCompositeChild(tree: LightSyntaxTree, node: LightNode) = tree.getChildren(node).any { !tree.isToken(it) }

    private fun collapsedBlock(tree: LightSyntaxTree, node: LightNode): Boolean {
        if (tree.getType(node) != KtNodeTypes.BLOCK) return false
        val children = tree.getChildren(node)
        val first = children.firstOrNull() ?: return false
        if (!tree.isToken(first) || tree.getType(first) != org.jetbrains.kotlin.kmp.lexer.KtTokens.LBRACE) return false
        return children.none { !tree.isToken(it) && hasCompositeChild(tree, it) }
    }

    private fun collapsedLambda(tree: LightSyntaxTree, node: LightNode): Boolean {
        if (tree.getType(node) != KtNodeTypes.LAMBDA_EXPRESSION) return false
        val literal = tree.findChildByType(node, KtNodeTypes.FUNCTION_LITERAL) ?: return false
        return tree.findChildByType(literal, KtNodeTypes.BLOCK) == null
    }

    private fun appendRendered(tree: LightSyntaxTree, node: LightNode, out: StringBuilder) {
        val type = tree.getType(node)
        if (tree.isToken(node)) {
            if (type in org.jetbrains.kotlin.kmp.lexer.KtTokens.WHITESPACES || type in org.jetbrains.kotlin.kmp.lexer.KtTokens.COMMENTS) return
            out.append(type.toString()); return
        }
        out.append('(').append(type.toString())
        for (c in tree.getChildren(node)) {
            val before = out.length
            out.append(' ')
            val mark = out.length
            appendRendered(tree, c, out)
            if (out.length == mark) out.setLength(before)
        }
        out.append(')')
    }
}
