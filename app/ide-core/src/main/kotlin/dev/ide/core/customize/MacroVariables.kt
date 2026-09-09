package dev.ide.core.customize

/**
 * The canonical macro-template variable names — the single list the editor offers (as insertable `$NAME$`
 * chips) and the resolver fills at expansion time. Kept here so the UI, the live resolver, and the preview
 * resolver never drift. Order is display order in the editor's variable palette.
 */
object MacroVariables {
    val NAMES: List<String> = listOf(
        "FILE",       // the file name (Example.kt)
        "CLASS",      // the file's base name, no extension (Example)
        "FILEPATH",   // the full path
        "EXPR",       // the expression/identifier immediately before the caret ("current expression")
        "SELECTION",  // the selected text, if any
        "LINE",       // 1-based line number
        "INDENT",     // the current line's leading whitespace
        "DATE",       // yyyy-MM-dd
        "TIME",       // HH:mm:ss
        "DATETIME",   // yyyy-MM-ddTHH:mm:ss
        "YEAR",       // yyyy
        "MONTH",      // MM
        "DAY",        // dd
        "USER",       // the OS user name
        "UUID",       // a fresh random UUID
    )
}
