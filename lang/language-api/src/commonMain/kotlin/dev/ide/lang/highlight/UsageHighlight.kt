package dev.ide.lang.highlight

import dev.ide.lang.dom.TextRange

/**
 * How a [UsageHighlight] touches its symbol, which the editor tints apart the way IntelliJ does
 * ("Identifier under caret" vs "Identifier under caret (write)").
 */
enum class UsageAccess {
    /** The symbol's value is read, or it is called or named as a type. */
    READ,

    /** The symbol is assigned (`x = …`, `x += …`, `x++`). */
    WRITE,

    /** The symbol's own declaration name. */
    DECLARATION,
}

/**
 * One name in a file that refers to the symbol under the caret: resolved, so a same-named symbol elsewhere in
 * the file (a local `x` in another function, an unrelated `name` field) is not one.
 */
data class UsageHighlight(val range: TextRange, val access: UsageAccess)
