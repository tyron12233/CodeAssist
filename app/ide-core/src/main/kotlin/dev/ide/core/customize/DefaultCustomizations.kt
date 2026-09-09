package dev.ide.core.customize

/**
 * The shipped defaults — the fallback used when neither the project nor the global scope defines its own. The
 * symbol list mirrors the historic hardcoded `EditorSymbolBar` so a fresh install looks exactly as before, and
 * "Reset to defaults" in the editor restores it. Built-in macros are seeded when the macro phase is wired.
 */
object DefaultCustomizations {

    /**
     * The default keyboard symbol-bar keys: the fixed (pinned) action group — Tab, line comment, move/duplicate
     * line — followed by the scrolling coding symbols. Mirrors the bar's historic layout, but every key is now
     * a customizable entry (was hardcoded chrome). "Reset to defaults" restores exactly this.
     */
    val SYMBOLS: List<SymbolKeyDef> = buildList {
        add(SymbolKeyDef.action("Tab", SymbolActions.TAB))
        add(SymbolKeyDef.action("//", SymbolActions.COMMENT))
        add(SymbolKeyDef.action("Move line up", SymbolActions.MOVE_LINE_UP))
        add(SymbolKeyDef.action("Move line down", SymbolActions.MOVE_LINE_DOWN))
        add(SymbolKeyDef.action("Duplicate line", SymbolActions.DUPLICATE_LINE))
        addAll(
            listOf(
                "{", "}", "(", ")", ";", "=", ".", ",", "\"", "'", ":", "<", ">", "/", "*",
                "[", "]", "+", "-", "&", "|", "!", "?", "@", "#", "_", "%", "\\",
            ).map { SymbolKeyDef.of(it) },
        )
    }

    /**
     * The shipped live-template macros, surfaced as **editable built-ins**: the same abbreviations the Java
     * (lang-jdt `LiveTemplates`) and Kotlin (lang-kotlin `KotlinKeywords`) backends emit, transcribed to the
     * user-facing template syntax. These aren't merged into the user scope — the language backends still emit
     * them (context-aware). The editor shows them so a user can edit or disable one (an override written to a
     * scope; the completion contributor then rewrites/removes the backend's item to match). The template text
     * is the starting point when editing; an unchanged built-in still expands via its backend.
     */
    val MACROS: List<MacroDef> = buildList {
        // --- Java (lang-jdt LiveTemplates) ---
        java("sout", "System.out.println(\$END\$);", "Print to standard output")
        java("souf", "System.out.printf(\"\$1\", \$END\$);", "Formatted print")
        java("soutv", "System.out.println(\${1:value});", "Print a value")
        java("serr", "System.err.println(\$END\$);", "Print to standard error")
        java("psvm", "public static void main(String[] args) {\n    \$END\$\n}", "main method")
        java("psf", "private static final \$END\$", "private static final")
        java("psfi", "private static final int \${1:NAME} = \$END\$;", "private static final int")
        java("psfs", "private static final String \${1:NAME} = \"\$END\$\";", "private static final String")
        java("fori", "for (int \${1:i} = 0; \$1 < \${2:n}; \$1++) {\n    \$END\$\n}", "Indexed for loop")
        java("iter", "for (var \${1:item} : \${2:iterable}) {\n    \$END\$\n}", "for-each loop")
        java("ife", "if (\${1:cond}) {\n    \$END\$\n}", "if statement")
        java("ifn", "if (\${1:o} == null) {\n    \$END\$\n}", "if null check")
        java("inn", "if (\${1:o} != null) {\n    \$END\$\n}", "if not-null check")
        java("whilet", "while (\${1:cond}) {\n    \$END\$\n}", "while loop")
        java("thr", "throw new \${1:RuntimeException}(\$END\$);", "throw an exception")
        java("try", "try {\n    \$END\$\n} catch (\${1:Exception} \${2:e}) {\n}", "try / catch")
        // --- Kotlin (lang-kotlin KotlinKeywords) ---
        kotlin("main", "fun main() {\n    \$END\$\n}", "main entry point")
        kotlin("maina", "fun main(args: Array<String>) {\n    \$END\$\n}", "main with args")
        kotlin("fun", "fun \${1:name}() {\n    \$END\$\n}", "function declaration")
        kotlin("val", "val \${1:name} = \$END\$", "read-only property")
        kotlin("var", "var \${1:name} = \$END\$", "mutable property")
        kotlin("if", "if (\${1:cond}) {\n    \$END\$\n}", "if statement")
        kotlin("ife", "if (\${1:cond}) {\n    \$END\$\n} else {\n}", "if / else")
        kotlin("for", "for (\${1:item} in \${2:items}) {\n    \$END\$\n}", "for-each loop")
        kotlin("forr", "for (\${1:i} in 0 until \${2:n}) {\n    \$END\$\n}", "indexed for loop")
        kotlin("while", "while (\${1:cond}) {\n    \$END\$\n}", "while loop")
        kotlin("when", "when (\${1:subject}) {\n    \$END\$\n}", "when expression")
        kotlin("try", "try {\n    \$END\$\n} catch (\${1:e}: Exception) {\n}", "try / catch")
    }

    private fun MutableList<MacroDef>.java(abbrev: String, template: String, description: String) =
        add(MacroDef(abbrev, template, description, languages = listOf("java"), builtIn = true))

    private fun MutableList<MacroDef>.kotlin(abbrev: String, template: String, description: String) =
        add(MacroDef(abbrev, template, description, languages = listOf("kotlin"), builtIn = true))
}
