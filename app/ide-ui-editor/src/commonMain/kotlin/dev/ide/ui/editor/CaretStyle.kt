package dev.ide.ui.editor

/**
 * How the editor draws the caret (Settings → Editor → Caret style). The id is what the settings store
 * persists, so renaming an entry is fine and changing an [id] is not.
 */
enum class CaretStyle(val id: String) {
    /** The thin vertical bar, the default. */
    Line("line"),
    /** A translucent box over the character after the caret, as in a terminal. */
    Block("block"),
    /** A bar under the character after the caret. */
    Underline("underline"),
    /** The bar as a little wax candle with a flickering flame on top. It does not blink: the flame moves. */
    Candle("candle"),
    ;

    companion object {
        /** The style persisted as [id], or [Line] for anything unknown (an older or hand-edited prefs file). */
        fun fromId(id: String?): CaretStyle = entries.firstOrNull { it.id == id } ?: Line
    }
}
