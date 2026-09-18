package dev.ide.lang.dom

import dev.ide.vfs.VirtualFile
import kotlin.jvm.JvmInline

/**
 * A backend-neutral DOM. IDE features (navigation, completion, refactor) target these types, never
 * JDT's ASTNode or javac's Tree directly. Each backend adapts its native tree to this interface, so
 * swapping JDT -> javac -> a custom parser is invisible above the SPI.
 *
 * Crucially the tree is ERROR-TOLERANT: a [ParsedFile] always covers the whole file even when the
 * source is syntactically invalid (which it almost always is while the user is typing). Broken
 * regions are represented as nodes of kind [NodeKind.ERROR]/[NodeKind.MISSING], not by throwing.
 */

/** Half-open text span [start, end) in UTF-16 offsets. */
data class TextRange(val start: Int, val end: Int) {
    val length: Int get() = end - start
    operator fun contains(offset: Int): Boolean = offset in start..end
    fun intersects(other: TextRange): Boolean = start < other.end && other.start < end
}

/**
 * Node classification. String-backed (not a closed enum) because the set is language-specific and
 * extensible; common kinds are provided as constants. A backend may define its own additional kinds.
 */
@JvmInline
value class NodeKind(val id: String) {
    companion object {
        val COMPILATION_UNIT = NodeKind("compilation_unit")
        val PACKAGE_DECL = NodeKind("package_decl")
        val IMPORT_DECL = NodeKind("import_decl")
        val CLASS_DECL = NodeKind("class_decl")
        val METHOD_DECL = NodeKind("method_decl")
        val FIELD_DECL = NodeKind("field_decl")
        val PARAMETER = NodeKind("parameter")
        val LOCAL_VAR = NodeKind("local_var")
        val BLOCK = NodeKind("block")
        val NAME_REF = NodeKind("name_ref")          // `foo`
        val MEMBER_ACCESS = NodeKind("member_access") // `foo.bar`
        val METHOD_CALL = NodeKind("method_call")
        val TYPE_REF = NodeKind("type_ref")
        val LITERAL = NodeKind("literal")

        /** A region the parser could not understand but recovered past. */
        val ERROR = NodeKind("error")
        /** A node the parser synthesized to keep the tree well-formed (e.g. a missing `)` ). */
        val MISSING = NodeKind("missing")
    }
}

interface DomNode {
    val kind: NodeKind
    val range: TextRange
    val parent: DomNode?
    val children: List<DomNode>

    /** Source text covered by this node (may be empty for synthetic/MISSING nodes). */
    fun text(): CharSequence
}

interface ParsedFile : DomNode {
    val file: VirtualFile
    /** The document version this tree was built from (see incremental package). Cheap staleness check. */
    val documentVersion: Long
    val diagnostics: List<Diagnostic>

    /** Deepest node whose range contains [offset], including ERROR/MISSING nodes. The entry point for "what's at the caret". */
    fun nodeAt(offset: Int): DomNode

    /** All nodes intersecting [range], pre-order. */
    fun nodesIn(range: TextRange): Sequence<DomNode>
}

data class Diagnostic(
    val range: TextRange,
    val severity: Severity,
    val message: String,
    val code: String? = null,
)

enum class Severity { ERROR, WARNING, INFO, HINT }

/**
 * The smallest syntactic range that strictly encloses `[selStart, selEnd)` — one press of Expand Selection.
 *
 * Language-neutral by construction: it walks the DOM from the caret outward and takes the first ancestor
 * that is wider than what is already selected, so it works for any backend that produces a [ParsedFile].
 * "Strictly wider on at least one side" is what makes repeated presses terminate at the file rather than
 * returning the same range forever.
 *
 * It lives here rather than in a host because it needs a parse and nothing else. Keeping it in the JVM
 * host's service layer was the only reason the iOS editor could not expand a selection.
 */
fun expandSelection(parsed: ParsedFile, selStart: Int, selEnd: Int, textLength: Int): TextRange? {
    val lo = minOf(selStart, selEnd).coerceIn(0, textLength)
    val hi = maxOf(selStart, selEnd).coerceIn(0, textLength)
    var node: DomNode? = parsed.nodeAt(lo)
    while (node != null) {
        val r = node.range
        if (r.start <= lo && r.end >= hi && (r.start < lo || r.end > hi)) return r
        node = node.parent
    }
    return null
}
