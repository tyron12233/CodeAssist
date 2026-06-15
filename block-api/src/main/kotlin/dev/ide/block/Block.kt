package dev.ide.block

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange

/**
 * The block model — a [BlockTree] is a live projection of the shared document/AST, not a parallel
 * model. Under full decomposition each [DomNode] maps to a [BlockNode] and the block tree mirrors the
 * AST; every block anchors to its DOM node and current text range, which is what makes caret
 * correspondence and surgical edits possible.
 *
 * A block is an ordered sequence of [BlockPart]s: read-only chrome fields (the literal syntax around
 * children — `if (`, `) {`, `else`), editable fields (a name, an operator, a literal), and slots
 * (child positions). The ordered [parts] list is the render/serialize order; [fields] and [slots] are
 * convenience views over it. Keeping order on the node lets the renderer and `serialize` reconstruct
 * the source exactly.
 */
interface BlockNode {
    /** Stable within one projected tree; used by [BlockEdit] refs and the UI. Not stable across reparses. */
    val id: BlockId
    val kind: NodeKind                 // mirrors the DOM node kind
    val anchor: DomNode?               // the AST node this projects (null for synthetic chrome)
    val range: TextRange               // current text span
    /** Fields and slots in source/render order. */
    val parts: List<BlockPart>

    /** A short human label for the block header (e.g. `if`, `method`, `call`). */
    val label: String get() = kind.id

    /** The [ValueKind] this block PRODUCES as an expression; [ValueKind.UNKNOWN] for statements/declarations. */
    val valueKind: ValueKind get() = ValueKind.UNKNOWN

    /** Inline editable tokens, in order — a convenience view over [parts]. */
    val fields: List<BlockField> get() = parts.mapNotNull { (it as? BlockPart.Field)?.field }
    /** Child positions, in order — a convenience view over [parts]. */
    val slots: List<BlockSlot> get() = parts.mapNotNull { (it as? BlockPart.Slot)?.slot }
}

/** One ordered piece of a block: either an inline [BlockField] or a child [BlockSlot]. */
sealed interface BlockPart {
    data class Field(val field: BlockField) : BlockPart
    data class Slot(val slot: BlockSlot) : BlockPart
}

/**
 * A child position. [category] constrains what may be placed here (the slot grammar); [multiple]
 * distinguishes a list slot (statements) from a single slot (a condition). [range] is the source span the
 * slot's content occupies — empty (start == end) for an empty single slot — so an insert/replace can be
 * expressed as a surgical edit without re-walking the DOM.
 */
interface BlockSlot {
    val category: SlotCategory
    val children: List<BlockNode>
    val multiple: Boolean
    val range: TextRange

    /**
     * The [ValueKind] this slot EXPECTS (an `if` condition expects [ValueKind.BOOLEAN]); falls back to
     * the content's produced kind when the position gives no expectation.
     */
    val valueKind: ValueKind get() = ValueKind.UNKNOWN
}

/**
 * An inline editable (or chrome) token. [role] names the token's purpose (`name`, `operator`, `keyword`,
 * `syntax`, `qualifier` — a pure-name receiver collapsed into a call block's header, e.g. `System.out`);
 * chrome punctuation uses `editable = false`. [range] is the token's source span (null for a purely
 * synthetic field with no backing text) — a `SetField` replaces exactly this span.
 */
data class BlockField(
    val role: String,
    val text: String,
    val editable: Boolean,
    val range: TextRange? = null,
) {
    companion object {
        /** Non-editable syntax chrome (`if (`, `) {`, `;`). */
        fun chrome(text: String, range: TextRange? = null): BlockField = BlockField("syntax", text, editable = false, range = range)
    }
}

/**
 * What may legally occupy a slot — the lightweight slot grammar that makes block editing safe (a
 * statement cannot be dropped into an expression slot). A closed set (an enum, per the repo convention
 * for closed classifications); [OPAQUE] accepts anything (raw-code / unmapped fallback).
 */
enum class SlotCategory {
    STATEMENT, EXPRESSION, TYPE, DECLARATION, NAME, MODIFIER, PARAMETER, ARGUMENT, OPAQUE;

    /** Whether a block of [other] may be placed in a slot of this category. [OPAQUE] accepts anything. */
    fun accepts(other: SlotCategory): Boolean = this == OPAQUE || this == other
}

/**
 * The VALUE type a slot expects or a block produces — drives the socket/pill SHAPE in the editor
 * (hexagon = boolean, pill = number, sharp rect = string, rounded = object, tag = type), the
 * Scratch convention. Orthogonal to [SlotCategory] (the structural grammar). A closed set (an enum,
 * per the repo convention); [UNKNOWN] renders the neutral shape. Inferred syntactically; a
 * [ValueKindOracle] can refine it semantically.
 */
enum class ValueKind { BOOLEAN, NUMBER, STRING, OBJECT, TYPE, UNKNOWN }

/**
 * Optional semantic refinement for [ValueKind]s. The projection engine asks the oracle first and
 * falls back to the mapping's syntactic heuristics on null, so a host can wire a resolver-backed
 * oracle (re-projecting when bindings land) without any mapping/engine change.
 */
fun interface ValueKindOracle {
    /** The kind [node] produces as an expression, or null when unknown / not an expression. */
    fun producedKind(node: DomNode): ValueKind?
}

/**
 * A projected file. [root] mirrors the [ParsedFile]; [version] is the document version it was projected
 * from (cheap staleness check, mirrors [ParsedFile.documentVersion]). [block]/[blockAt] resolve refs and
 * carets back to blocks.
 */
interface BlockTree {
    val root: BlockNode
    val version: Long

    /** The block with [id] anywhere in the tree, or null. */
    fun block(id: BlockId): BlockNode?

    /** Deepest block whose range contains [offset] (caret → block), or null if outside the root. */
    fun blockAt(offset: Int): BlockNode?
}

/** Stable-within-a-projection block identity — a `@JvmInline value class`, never a stringly-typed API. */
@JvmInline
value class BlockId(val value: String)

/** A reference to a block by id, resolved against the current [BlockTree]. */
data class BlockRef(val id: BlockId)

/**
 * A reference to a position in a slot: the [owner] block, the [slotIndex] among its [BlockNode.slots],
 * and [index] within that slot's children (== children.size to append). For a single (non-multiple) slot
 * [index] is 0.
 */
data class SlotRef(val owner: BlockId, val slotIndex: Int, val index: Int = 0)
