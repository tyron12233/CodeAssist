package dev.ide.lang.jdt.folding

import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.Block
import org.eclipse.jdt.core.dom.Comment
import org.eclipse.jdt.core.dom.ImportDeclaration

/**
 * Java code folding over the JDT AST (binding resolution not needed — pure structure). Mirrors
 * [dev.ide.lang.jdt.highlight.JdtSemanticHighlighter]'s parse-and-walk shape:
 *  - the import group → `import ...` (collapsed by default, like IntelliJ);
 *  - type bodies (class/interface/enum/annotation/record) and statement `Block`s (method/constructor bodies,
 *    control flow) → `{...}` (the region spans only the text between the braces, so they stay visible);
 *  - multi-line block comments / Javadoc → `/*...*/`.
 *
 * Only regions spanning more than one line are emitted. Polls [EngineCancellation] so completion preempts.
 */
class JdtCodeFolder(private val analyzer: JdtSourceAnalyzer) : FoldingService {

    /** Last computed fold set per file, keyed by the exact buffer content. Folding is a pure function of the
     *  text, so an identical buffer (a preempt-retry of the FOLDS pass, or the daemon's re-run once the index
     *  finishes) reuses it and — the real win here — SKIPS the JDT `parseSyntactic`. Bounded by open files. */
    private class Cached(val text: String, val regions: List<FoldRegion>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    override suspend fun folds(file: VirtualFile): List<FoldRegion> {
        val text = analyzer.overlayProvider()[analyzer.fqcnFor(file)]?.let { String(it) }
            ?: runCatching { file.readText().toString() }.getOrNull()
            ?: return emptyList()
        cache[file.path]?.let { if (it.text == text) return it.regions }
        val parsed = analyzer.parseSyntactic(file, text) // folding is structural; no bindings needed
        val cu = parsed.cu
        val out = ArrayList<FoldRegion>(32)

        // The import group: first import → last import, shown as `import ...`.
        @Suppress("UNCHECKED_CAST")
        (cu.imports() as List<ImportDeclaration>).takeIf { it.isNotEmpty() }?.let { imports ->
            val start = imports.first().startPosition
            val last = imports.last()
            addRegion(out, text, start, last.startPosition + last.length, "import ...", FoldKind.IMPORTS, collapsedByDefault = true)
        }

        cu.accept(object : ASTVisitor() {
            private var seen = 0
            private fun tick() { if (seen++ % 64 == 0) EngineCancellation.checkCanceled() }

            override fun visit(node: Block): Boolean {
                tick()
                // A Block's range is `{ … }`; fold strictly between the braces.
                braces(out, text, node, FoldKind.BLOCK)
                return true
            }
            override fun visit(node: org.eclipse.jdt.core.dom.TypeDeclaration): Boolean { tick(); typeBody(node); return true }
            override fun visit(node: org.eclipse.jdt.core.dom.EnumDeclaration): Boolean { tick(); typeBody(node); return true }
            override fun visit(node: org.eclipse.jdt.core.dom.AnnotationTypeDeclaration): Boolean { tick(); typeBody(node); return true }
            override fun visit(node: org.eclipse.jdt.core.dom.RecordDeclaration): Boolean { tick(); typeBody(node); return true }

            /** A type body: from the `{` after the type name to the closing `}` (the declaration's last char). */
            private fun typeBody(node: AbstractTypeDeclaration) {
                val open = text.indexOf('{', startIndex = node.name.startPosition + node.name.length)
                val close = node.startPosition + node.length - 1
                if (open in 0 until close) addRegion(out, text, open + 1, close, "...", FoldKind.CLASS_BODY)
            }
        })

        @Suppress("UNCHECKED_CAST")
        for (c in (cu.commentList as List<Comment>)) {
            if (!c.isBlockComment && !c.isDocComment) continue // line comments don't fold
            addRegion(out, text, c.startPosition, c.startPosition + c.length, if (c.isDocComment) "/**...*/" else "/*...*/", FoldKind.COMMENT)
        }
        cache[file.path] = Cached(text, out)
        return out
    }

    private fun braces(out: MutableList<FoldRegion>, text: String, node: ASTNode, kind: FoldKind) {
        // `{` is the first char, `}` the last; fold the interior.
        addRegion(out, text, node.startPosition + 1, node.startPosition + node.length - 1, "...", kind)
    }

    private fun addRegion(out: MutableList<FoldRegion>, text: String, start: Int, end: Int, placeholder: String, kind: FoldKind, collapsedByDefault: Boolean = false) {
        if (start < 0 || end <= start || end > text.length) return
        if (text.indexOf('\n', start).let { it == -1 || it >= end }) return // single line → not foldable
        out += FoldRegion(TextRange(start, end), placeholder, kind, collapsedByDefault)
    }
}
