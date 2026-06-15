package dev.ide.block

/**
 * A structural edit issued by the block view. Every variant maps to a minimal
 * [dev.ide.lang.incremental.DocumentEdit] for the affected range — `serialize` for new nodes,
 * a token-range replace for a field — so everything outside that range is byte-for-byte untouched. The
 * edit then flows through the one shared document: incremental reparse → incremental re-projection → both
 * the text view and the block view update. There is no view-to-view sync.
 *
 * Refs ([BlockRef]/[SlotRef]) resolve against the current [BlockTree] the edit was computed against.
 */
sealed interface BlockEdit

/** Insert a template at a slot position (palette drag / tap-insert). */
data class InsertTemplate(val at: SlotRef, val template: BlockTemplate) : BlockEdit

/** Move a block to another slot position (drag / gesture) — a delete + insert in one surgical pass. */
data class Move(val block: BlockRef, val to: SlotRef) : BlockEdit

/** Delete a block and the syntax that only existed to hold it (trailing `;`/newline for a statement). */
data class Delete(val block: BlockRef) : BlockEdit

/** Wrap a block in a control template (wrap a statement in `if`/`for`/`try`). */
data class Wrap(val block: BlockRef, val with: BlockTemplate) : BlockEdit

/** Set an editable field's text in place (a name, an operator, a literal). */
data class SetField(val block: BlockRef, val role: String, val text: String) : BlockEdit

/**
 * Replace a slot's content with raw text, to be reparsed and re-projected into sub-blocks — the
 * type-text→blocks escape valve and the transient-text-slot path for mid-edit invalidity.
 */
data class ReplaceWithText(val slot: SlotRef, val text: String) : BlockEdit
