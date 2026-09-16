package dev.ide.kotlin.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lazy parsing and on-demand body expansion.
 *
 * The property that has to hold, and the only one worth testing hard: **expanding a body must give the same
 * tree a full parse would**, at the same offsets. If it does not, then every offset the editor reports from
 * inside a function is wrong, which is the kind of bug that looks like a completely different bug.
 */
class IncrementalKotlinParseTest {

    private val source = """
        package demo

        fun outer(n: Int): Int {
            val doubled = n * 2
            if (doubled > 10) {
                return doubled
            }
            return n
        }

        class Holder {
            fun inner(): String {
                val parts = listOf("a", "b")
                return parts.joinToString()
            }
        }
    """.trimIndent()

    // --- lazy parsing ------------------------------------------------------------------------------------

    @Test
    fun aLazyParseLeavesBodiesUnexpanded() {
        val lazy = KotlinSyntax.parse(source, lazy = true).render()
        val full = KotlinSyntax.parse(source).render()
        // A collapsed body keeps its TOKENS and loses only its structure, so the test is the absence of
        // composites inside the block rather than the absence of children.
        assertTrue(
            Regex("\\(BLOCK [A-Z_a-z ]+\\)").containsMatchIn(lazy),
            "a lazy parse leaves a body as tokens with no structure; got $lazy",
        )
        assertTrue(full.contains("(BLOCK LBRACE (PROPERTY"), "a full parse descends into them; got $full")
        assertTrue(lazy.length < full.length, "the lazy tree must be the smaller one")
    }

    @Test
    fun aLazyParseStillSeesEveryDeclaration() {
        // Collapsing bodies must not cost the file's shape, or lazy mode would be useless for structure.
        val lazy = KotlinSyntax.parse(source, lazy = true).render()
        assertTrue(lazy.contains("(FUN"), lazy)
        assertTrue(lazy.contains("(CLASS"), lazy)
        assertEquals(2, Regex("\\(FUNCTION\\b").findAll(lazy).count(), "both functions are visible: $lazy")
    }

    // --- expansion fidelity ------------------------------------------------------------------------------

    @Test
    fun anExpandedBodyMatchesWhatAFullParseProduces() {
        val incremental = IncrementalKotlinParse(source)
        val caret = source.indexOf("val doubled")
        val expanded = assertNotNull(incremental.blockAt(caret), "the caret is inside `outer`'s body")

        // The same region out of a full parse of the whole file.
        val full = KotlinSyntax.parse(source)
        val counterpart = assertNotNull(
            blockCovering(full, full.getRoot(), caret),
            "the full parse has a body here too",
        )

        assertEquals(
            renderFrom(full, counterpart),
            renderFrom(expanded.tree, expanded.tree.getRoot()),
            "an expanded body must be the tree a full parse would have built",
        )
    }

    @Test
    fun anExpandedBodyCarriesAbsoluteOffsets() {
        // The whole reason `parseBlock` takes a start offset. Relative offsets would line up with nothing.
        val incremental = IncrementalKotlinParse(source)
        val caret = source.indexOf("val parts")
        val expanded = assertNotNull(incremental.blockAt(caret))
        val root = expanded.tree.getRoot()
        assertTrue(
            caret in expanded.startOf(root)..expanded.endOf(root),
            "translated through ExpandedBlock, the caret falls inside the body it came from",
        )
        assertEquals(
            "{",
            source.substring(expanded.startOf(root), expanded.startOf(root) + 1),
            "the translated start must land on the body's own brace",
        )
        // Every node must sit inside its parent once translated. This is the assertion that catches the
        // mixed-coordinate trap: markers shifted, tokens not.
        for (child in expanded.tree.getChildren(root)) {
            assertTrue(
                expanded.startOf(child) >= expanded.startOf(root) &&
                    expanded.endOf(child) <= expanded.endOf(root),
                "every child sits inside its parent's range, tokens included",
            )
        }
    }

    @Test
    fun theOutermostBodyIsTheUnitOfExpansion() {
        // Worth stating because the opposite is the natural guess: lazy mode collapses the OUTERMOST body and
        // everything inside it, so an `if` nested in a function is not its own collapsed block. Asking from
        // inside the `if` hands back the whole function, and expanding that reveals the `if` with structure.
        val incremental = IncrementalKotlinParse(source)
        val caret = source.indexOf("return doubled")
        val expanded = assertNotNull(incremental.blockAt(caret))
        val rendered = expanded.tree.render()
        assertTrue(rendered.contains("(PROPERTY"), "the function's own statements are there: $rendered")
        assertTrue(rendered.contains("(IF"), "and so is the nested `if`, now with structure: $rendered")
    }

    @Test
    fun anOffsetOutsideAnyBodyHasNoBlock() {
        val incremental = IncrementalKotlinParse(source)
        assertNull(incremental.blockAt(source.indexOf("package")), "the package directive is in no body")
    }

    // --- caching across edits ----------------------------------------------------------------------------

    @Test
    fun anUnchangedBodyIsNotReparsed() {
        val incremental = IncrementalKotlinParse(source)
        val caret = source.indexOf("val doubled")
        val first = assertNotNull(incremental.blockAt(caret))
        val again = assertNotNull(incremental.blockAt(caret))
        assertSame(first, again, "asking twice must not parse twice")
        assertEquals(1, incremental.blockParses)
        assertEquals(1, incremental.blockCacheHits)
    }

    @Test
    fun editingABodyReparsesThatBodyAndKeepsTheOnesAboveIt() {
        val incremental = IncrementalKotlinParse(source)
        val upper = source.indexOf("val doubled")
        val lower = source.indexOf("val parts")
        incremental.blockAt(upper)
        incremental.blockAt(lower)
        assertEquals(2, incremental.blockParses)

        // Type inside the LOWER body. The upper one has not moved and has not changed.
        val edited = source.replace("val parts = listOf(\"a\", \"b\")", "val parts = listOf(\"a\", \"b\", \"c\")")
        incremental.edit(edited)

        val hitsBefore = incremental.blockCacheHits
        incremental.blockAt(upper)
        assertEquals(hitsBefore + 1, incremental.blockCacheHits, "the body above the edit survives")
        assertEquals(2, incremental.blockParses, "and is not parsed again")

        incremental.blockAt(edited.indexOf("val parts"))
        assertEquals(3, incremental.blockParses, "the edited body is parsed afresh")
    }

    // --- skipping the file parse -------------------------------------------------------------------------

    @Test
    fun anEditInsideABodyDoesNotReparseTheFile() {
        val incremental = IncrementalKotlinParse(source)
        val caret = source.indexOf("val doubled")
        incremental.blockAt(caret)
        val before = incremental.fileReparses

        val edited = source.replace("val doubled = n * 2", "val doubled = n * 2; val extra = 1")
        incremental.edit(edited)

        assertEquals(1, incremental.fileReparsesSkipped, "the edit is inside a body and changes nothing outside")
        assertEquals(before, incremental.fileReparses, "so the file must not have been reparsed")
        assertNotNull(incremental.blockAt(caret), "and the body is still reachable without one")
    }

    @Test
    fun typingAClosingBraceForcesAFullReparse() {
        // The proof obligation. A `}` ends the body early, so everything after it belongs to something else
        // and none of the old structure can be trusted.
        val incremental = IncrementalKotlinParse(source)
        incremental.blockAt(source.indexOf("val doubled"))
        val before = incremental.fileReparses

        incremental.edit(source.replace("val doubled = n * 2", "val doubled = n * 2 }"))

        assertEquals(0, incremental.fileReparsesSkipped, "an unbalanced body cannot be absorbed")
        assertTrue(incremental.fileReparses > before, "the file has to be reparsed")
    }

    @Test
    fun aBraceInsideAStringIsNotABrace() {
        // Why the balance check lexes instead of scanning characters.
        val incremental = IncrementalKotlinParse(source)
        incremental.blockAt(source.indexOf("val doubled"))
        incremental.edit(source.replace("val doubled = n * 2", "val doubled = \"}\".length"))
        assertEquals(1, incremental.fileReparsesSkipped, "a brace in a string leaves the body balanced")
    }

    @Test
    fun anEditOutsideAnyBodyReparsesTheFile() {
        val incremental = IncrementalKotlinParse(source)
        incremental.blockAt(source.indexOf("val doubled"))
        val before = incremental.fileReparses
        incremental.edit(source.replace("fun outer(n: Int)", "fun outer(n: Long)"))
        assertEquals(0, incremental.fileReparsesSkipped, "a signature change is not body-local")
        assertTrue(incremental.fileReparses > before)
    }

    @Test
    fun aStaleFileTreeIsCorrectOnceItIsAskedFor() {
        // The fast path defers the file parse; it must not lose it. Reading the tree has to give the truth.
        val incremental = IncrementalKotlinParse(source)
        incremental.blockAt(source.indexOf("val doubled"))
        val edited = source.replace("val doubled = n * 2", "val doubled = n * 2; val extra = 1")
        incremental.edit(edited)
        assertEquals(1, incremental.fileReparsesSkipped)

        assertEquals(
            KotlinSyntax.parse(edited, lazy = true).render(),
            incremental.fileTree.render(),
            "the deferred tree must equal a fresh parse of the current buffer",
        )
    }

    @Test
    fun theFileTreeFollowsTheBuffer() {
        val incremental = IncrementalKotlinParse(source)
        assertEquals(2, Regex("\\(FUNCTION\\b").findAll(incremental.fileTree.render()).count())
        incremental.edit("$source\n\nfun added() {}")
        assertEquals(
            3,
            Regex("\\(FUNCTION\\b").findAll(incremental.fileTree.render()).count(),
            "a declaration added to the buffer must appear in the tree",
        )
    }

    @Test
    fun brokenCodeStillExpands() {
        // The normal case for an editor: a body being typed into.
        val broken = "fun f() {\n    val x = \n}"
        val incremental = IncrementalKotlinParse(broken)
        val expanded = incremental.blockAt(broken.indexOf("val x"))
        assertNotNull(expanded, "an unterminated statement is still inside a body")
    }

    private fun blockCovering(
        tree: org.jetbrains.kotlin.kmp.tree.LightSyntaxTree,
        node: org.jetbrains.kotlin.kmp.tree.LightNode,
        offset: Int,
    ): org.jetbrains.kotlin.kmp.tree.LightNode? {
        if (offset < tree.getStartOffset(node) || offset > tree.getEndOffset(node)) return null
        for (child in tree.getChildren(node)) blockCovering(tree, child, offset)?.let { return it }
        return if (tree.getType(node) == org.jetbrains.kotlin.kmp.parser.KtNodeTypes.BLOCK) node else null
    }

    private fun renderFrom(
        tree: org.jetbrains.kotlin.kmp.tree.LightSyntaxTree,
        node: org.jetbrains.kotlin.kmp.tree.LightNode,
    ): String {
        val out = StringBuilder()
        fun walk(n: org.jetbrains.kotlin.kmp.tree.LightNode) {
            val type = tree.getType(n).toString()
            if (tree.isToken(n)) {
                out.append(type)
                return
            }
            out.append('(').append(type)
            for (child in tree.getChildren(n)) {
                val childType = tree.getType(child).toString()
                if (childType == "WHITE_SPACE" || childType.endsWith("COMMENT")) continue
                out.append(' ')
                walk(child)
            }
            out.append(')')
        }
        walk(node)
        return out.toString()
    }
}
