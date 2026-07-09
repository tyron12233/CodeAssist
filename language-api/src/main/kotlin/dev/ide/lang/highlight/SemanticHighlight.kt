package dev.ide.lang.highlight

import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile

/**
 * Semantic highlighting SPI — the type-aware second layer over the editor's lexical highlighter.
 *
 * The lexical layer (a per-line scanner in the UI) is fast, synchronous, and guesses by shape: a
 * Capitalized word is a "type", a word before `(` is a "call". The semantic layer runs on the engine
 * thread off a RESOLVED parse and replaces those guesses with the truth — this name is a field not a
 * local, that call is a `@Composable`, this `Foo` is really a variable. A backend produces a flat list of
 * [SemanticToken]s (identifier ranges + classification); the UI overlays them on the lexical spans,
 * semantic winning on overlap.
 *
 * Extensible the same way every other editor capability is: a backend opts in by advertising
 * [dev.ide.lang.BackendCapability.SEMANTIC_HIGHLIGHT] and returning a [SemanticHighlightService] from its
 * analyzer (see [dev.ide.lang.SourceAnalyzer.semanticHighlighter]). Adding a language's semantic colors is a
 * backend registration, not a host edit; [HighlightKind] is an open, string-backed set so a backend can
 * contribute kinds the core doesn't enumerate (the UI falls back to the kind's nearest base color).
 */
interface SemanticHighlightService {
    /**
     * Classify the identifier tokens in [file]'s current (tolerant) parse. Runs on the shared engine thread,
     * debounced by the host like analysis/inlay-hints, and is expected to poll
     * [dev.ide.platform.EngineCancellation] so a higher-priority call (completion) can preempt the pass.
     * Returns ranges in document offsets; ranges that don't classify are simply omitted (the lexical layer
     * still colors them). Order is unspecified; overlapping tokens are resolved by the consumer (last wins).
     */
    suspend fun highlight(file: VirtualFile): List<SemanticToken>
}

/**
 * One classified identifier occurrence: its source [range], what it IS ([kind]), and orthogonal facts about
 * it ([modifiers] — static, a `val`, an extension, `@Composable`, …). The UI maps `kind` to a base color and
 * layers `modifiers` as color tweaks / font styles (italic for extensions, etc.).
 */
class SemanticToken(
    val range: TextRange,
    val kind: HighlightKind,
    val modifiers: Set<HighlightModifier> = emptySet(),
)

/**
 * What an identifier is. Open and string-backed (the `NodeKind`/`LanguageId` convention) so a backend may
 * contribute kinds beyond these built-ins; an unknown kind degrades to its nearest base color in the UI.
 */
@JvmInline
value class HighlightKind(val id: String) {
    companion object {
        val NAMESPACE = HighlightKind("namespace")          // a package / import segment
        val CLASS = HighlightKind("class")
        val INTERFACE = HighlightKind("interface")
        val ENUM = HighlightKind("enum")
        val ANNOTATION = HighlightKind("annotation")        // an annotation type used as `@Foo`
        val OBJECT = HighlightKind("object")                // Kotlin object / companion
        val TYPE_PARAMETER = HighlightKind("typeParameter") // `T`, `R`
        val FUNCTION = HighlightKind("function")            // top-level / standalone function (Kotlin)
        val METHOD = HighlightKind("method")                // a member function / Java method
        val CONSTRUCTOR = HighlightKind("constructor")
        val PROPERTY = HighlightKind("property")            // a Kotlin property / accessor-backed member
        val FIELD = HighlightKind("field")                  // a Java field / backing field
        val ENUM_CONSTANT = HighlightKind("enumConstant")

        /** A compile-time / immutable-static constant — Kotlin `const val`, a Java `static final` field.
         *  Colored apart from a regular property so a constant reads as a constant. */
        val CONSTANT = HighlightKind("constant")
        val PARAMETER = HighlightKind("parameter")
        val LOCAL_VARIABLE = HighlightKind("localVariable")

        /** A Kotlin label — a definition (`loop@`), a jump target (`break@loop`, `return@loop`), or the
         *  labeled `this`/`super` qualifier (`this@Outer`). Purely syntactic; the lexer leaves it uncolored. */
        val LABEL = HighlightKind("label")

        /** A string-template interpolation delimiter — the `$` of `$name`, or the `${`/`}` of `${expr}`.
         *  Colored distinctly so an interpolation stands out from the surrounding string literal. */
        val STRING_TEMPLATE_ENTRY = HighlightKind("stringTemplateEntry")

        /** An escape sequence inside a string literal (`\n`, `\t`, `\uXXXX`, `\$`). */
        val STRING_ESCAPE = HighlightKind("stringEscape")

        val KEYWORD = HighlightKind("keyword")
    }
}

/** Orthogonal facts layered on top of a [HighlightKind] — they tweak color and/or font style, never the base kind. */
enum class HighlightModifier {
    /** The defining occurrence (the declaration's own name), as opposed to a use. */
    DECLARATION,
    STATIC,
    ABSTRACT,
    DEPRECATED,
    /** `val` / Java `final` — an immutable binding. */
    READONLY,
    /** `var` / a reassignable binding (IntelliJ underlines these). */
    MUTABLE,
    /** A Kotlin extension function/property. */
    EXTENSION,
    /** A `@Composable` function (declaration or a call to one). */
    COMPOSABLE,
    /** A `suspend` function (declaration or a call to one). */
    SUSPEND,
}
