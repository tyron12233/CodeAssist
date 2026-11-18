package dev.codeassist.csharp.editor.diagnostics


import dev.ide.analysis.DiagnosticTag

/**
 * Maps C# compiler warning codes to editor diagnostic tags.
 */
internal object CsDiagnosticTags {

    /** Obsolete member usage. */
    val DEPRECATED = setOf(
        "CS0612", // Obsolete, no message
        "CS0618", // Obsolete, with a message
        "CS0672", // Overrides an obsolete member
        "CS0809", // Obsolete member overrides a non-obsolete member
    )

    /** Declarations that are unused or assigned but never read. */
    val UNUSED = setOf(
        "CS0168", // Unused local variable
        "CS0219", // Local variable assigned but never read
        "CS0169", // Unused field
        "CS0414", // Field assigned but never read
    )

    fun of(code: String?): Set<DiagnosticTag> = when (code) {
        in DEPRECATED -> setOf(DiagnosticTag.DEPRECATED)
        in UNUSED -> setOf(DiagnosticTag.UNUSED)
        else -> emptySet()
    }
}
