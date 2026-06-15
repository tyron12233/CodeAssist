package dev.ide.block

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange
import dev.ide.platform.ExtensionPoint

/**
 * How a node kind becomes a block. An extension point so plugins add mappings for new
 * languages/constructs (Kotlin, XML) without the engine knowing them. For full decomposition a mapping
 * is provided per node kind; anything a registered mapping does not [handles] is covered by the
 * engine's opaque fallback (a raw, editable text block — never a dead end).
 *
 * A mapping's [handles] set is also the decomposition boundary: a child whose kind is handled is
 * projected as its own (sub-)block; a child whose kind is not handled collapses to a single editable
 * text slot (the expression-collapse knob, which is also the type-text→blocks escape valve).
 */
interface BlockMapping {
    val languages: Set<LanguageId>
    val handles: Set<NodeKind>

    /**
     * Project [node] into a block. The default delegates to [ProjectionContext.carve] — the generic
     * interleave of source "chrome" with recursively-projected child slots — which is enough for most
     * kinds. Override only to add affordances a kind needs (e.g. an `if`'s explicit `+ else`).
     */
    fun project(node: DomNode, ctx: ProjectionContext): BlockNode = ctx.carve(node)

    /** The palette / tap-insert template for this construct. */
    fun template(): BlockTemplate

    /**
     * A NEW or directly-edited block → minimal source text. Runs ONLY on created/edited nodes, never the
     * whole file (which preserves untouched code verbatim). The default reconstructs the text from
     * the block's ordered [parts] (chrome verbatim, slots from their children).
     */
    fun serialize(block: BlockNode): String = defaultSerialize(block)
}

/**
 * A palette / tap-insert template. [defaultText] is inserted with slot placeholders; the caret
 * lands at the first placeholder. [category] is the slot category this template satisfies (so tap-insert
 * menus and drag-snap targets can filter); [slots] lists the categories of the holes it opens.
 */
data class BlockTemplate(
    val label: String,
    val category: SlotCategory,
    val defaultText: String,            // e.g. "if (█) {\n    █\n}" — █ marks a placeholder slot
    val slots: List<SlotCategory> = emptyList(),
) {
    companion object {
        /** The placeholder marker in [defaultText] (a slot to fill / where the caret should land). */
        const val PLACEHOLDER: Char = '█'
    }
}

/**
 * The seam a [BlockMapping] uses to build blocks and recurse, without depending on the engine's concrete
 * block types. The engine implements it: [carve] / [child] drive recursion and id assignment; the factory
 * methods ([block]/[slot]/[field]) construct the engine's nodes.
 */
interface ProjectionContext {
    /** The whole file's source text (so a mapping can slice chrome between child ranges). */
    val source: CharSequence

    /** Source text covered by [range]. */
    fun textOf(range: TextRange): CharSequence

    /**
     * Generic projection of [node]: interleave the literal source between child ranges as chrome
     * [BlockField]s with each child recursively projected (via [child]) into a slot. The workhorse —
     * works for any kind and is the default [BlockMapping.project].
     */
    fun carve(node: DomNode): BlockNode

    /**
     * Project a child [node] into a block. If some registered mapping [handles][BlockMapping.handles] its
     * kind it decomposes; otherwise it becomes a single editable text slot (opaque/collapse).
     */
    fun child(node: DomNode): BlockNode

    // ---- node factories (the engine assigns ids) ----

    fun block(anchor: DomNode?, kind: NodeKind, parts: List<BlockPart>, label: String? = null, valueKind: ValueKind = ValueKind.UNKNOWN): BlockNode
    fun slot(category: SlotCategory, children: List<BlockNode>, multiple: Boolean, range: TextRange, valueKind: ValueKind = ValueKind.UNKNOWN): BlockSlot
    fun field(role: String, text: String, editable: Boolean, range: TextRange? = null): BlockField
}

/** The extension point plugins contribute [BlockMapping]s to. */
val BLOCK_MAPPING_EP: ExtensionPoint<BlockMapping> = ExtensionPoint("platform.blockMapping")

/** Default [BlockMapping.serialize]: concatenate parts in order — chrome verbatim, slots from children. */
fun defaultSerialize(block: BlockNode): String = buildString {
    for (part in block.parts) when (part) {
        is BlockPart.Field -> append(part.field.text)
        is BlockPart.Slot -> part.slot.children.forEach { append(defaultSerialize(it)) }
    }
}
