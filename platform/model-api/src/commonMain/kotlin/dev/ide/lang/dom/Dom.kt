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

        /**
         * A string literal, in every language that has one: Java's `"x"` and its text blocks, Kotlin's
         * `"x"`, `"a $x b"` and `"""…"""`.
         *
         * Split out of [LITERAL] because a string is the one literal an editor feature routinely treats
         * differently from the rest (a color, a resource reference, a regex, a path), and because the
         * backends previously spelled it three ways: `literal` from both Java backends, `kt.string_template`
         * from Kotlin. A pattern over strings could not be written once.
         *
         * A Kotlin string is still a template. Its interpolations are `kt.string_template_interpolation`
         * children, so a node of this kind is a compile-time constant only when it has none.
         */
        val STRING_LITERAL = NodeKind("string_literal")

        /** The `(…)` of a call or constructor call: the parent of its arguments. */
        val ARGUMENT_LIST = NodeKind("argument_list")

        /**
         * One argument inside an [ARGUMENT_LIST], where the language gives it a node of its own.
         *
         * Kotlin does, because `name = value` and a spread need somewhere to live. Java does not: its
         * arguments are the expressions themselves, directly under the [ARGUMENT_LIST]. Both shapes are
         * legal, so read arguments through [argumentNodes] rather than assuming either.
         */
        val ARGUMENT = NodeKind("argument")

        /**
         * A constructor call: Java's `new Foo(…)`, Kotlin's `: Base(…)` supertype call.
         *
         * A Kotlin `Foo()` is an ordinary [METHOD_CALL], because nothing but resolution can tell a
         * constructor from a function of the same name. Match both kinds when you mean "something is being
         * invoked here" (see `DomPatterns.call(name)`).
         */
        val CONSTRUCTOR_CALL = NodeKind("constructor_call")

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

// ---------------------------------------------------------------------------
// Call navigation
//
// Two questions every pattern over a call asks first, answered once here rather than in each caller,
// because the backends do not agree on the shape and a caller that picks one is wrong on the other
// language. See [NodeKind.ARGUMENT].
// ---------------------------------------------------------------------------

/** A bare identifier, after qualification and type arguments have been cut off [calleeName]. */
private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_]*""")

/**
 * The argument VALUES of a call, whichever shape the backend's tree has: the expressions directly under
 * the [NodeKind.ARGUMENT_LIST] (Java), or the value inside each [NodeKind.ARGUMENT] wrapper (Kotlin).
 *
 * Call it on the call node or on the argument list itself. A named Kotlin argument yields its value, not
 * its `name =` half, since that is what a caller reading arguments wants; reach the name through the
 * [NodeKind.ARGUMENT] node's own children when you need it.
 *
 * Only the parenthesised arguments. A Kotlin trailing lambda is a `kt.lambda_argument` sibling of the
 * argument list rather than a member of it, and is deliberately not included: `f(1) { … }` has one
 * argument here, which is what a pattern keyed on argument position needs.
 *
 * Empty for a call from the legacy `:lang-jdt` backend, whose tree has no argument-list node to adapt
 * (JDT models arguments as a child list property). `:lang-java` is the editor's Java backend and does.
 */
fun DomNode.argumentNodes(): List<DomNode> {
    val list = when {
        kind == NodeKind.ARGUMENT_LIST -> this
        else -> children.firstOrNull { it.kind == NodeKind.ARGUMENT_LIST } ?: return emptyList()
    }
    val out = ArrayList<DomNode>(list.children.size)
    for (child in list.children) {
        // An ARGUMENT's value is its LAST child: a named argument puts the `name =` half first. An empty
        // one is error recovery mid-typing, and the wrapper is the most useful thing left to hand back.
        if (child.kind == NodeKind.ARGUMENT) out += child.children.lastOrNull() ?: child else out += child
    }
    return out
}

/**
 * The simple name being invoked by a call node, or null when the head is not a name.
 *
 * SYNTACTIC, and deliberately so: it is the cheap gate to run before resolving, on the pass that sees
 * every call in the file. It answers `"Color"` for `Color(…)`, `androidx.compose.ui.graphics.Color(…)`,
 * `Color<Int>(…)` and Java's `new Color(…)` alike. Two different `Color`s in scope look identical here,
 * so anything that must be right about WHICH one resolves the callee afterwards.
 */
fun DomNode.calleeName(): String? {
    val head = children.firstOrNull { it.kind != NodeKind.ARGUMENT_LIST } ?: return null
    val bare = head.text().toString()
        .substringBefore('<')          // `foo<T>(…)`
        .trim()
        .substringAfterLast('.')       // Java's callee carries its own qualifier; Kotlin's does not
        .trim()
        .trim('`')                     // Kotlin's `` `is` ``
    return bare.takeIf { IDENTIFIER.matches(it) }
}
