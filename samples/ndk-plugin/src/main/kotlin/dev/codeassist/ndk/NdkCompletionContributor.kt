package dev.codeassist.ndk

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.platform.log.Logger
import java.nio.file.Paths

/**
 * C and C++ completion, answered by the compiler.
 *
 * clang has a mode for exactly this (`-code-completion-at`), so the candidates come from the same frontend
 * that compiles the file: members of the real type behind a `.`, macros the real headers define, overloads
 * with their real parameters. That is the whole reason this plugin ships no language server — a second
 * frontend would be another 25 MB and another thing to disagree with the first.
 *
 * What it costs is a parse per request. The headers at the top of the file are precompiled once and loaded
 * rather than re-parsed (see [ClangPreamble]), so that parse is the user's own code; on top of that the
 * engine debounces, and this caches on the buffer outside the word being typed, so narrowing a popup
 * re-filters what clang already said instead of asking again.
 */
class NdkCompletionContributor(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : CompletionContributor {

    override val id = "ndk.clang"

    private var cachedKey: List<Any>? = null
    private var cached: List<ClangCompletion> = emptyList()

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val toolchain = toolchain() ?: return
        val path = runCatching { Paths.get(params.document.file.path) }.getOrNull() ?: return
        val text = params.document.text.toString()

        val candidates = candidatesFor(toolchain, path, text, params) ?: return
        val cpp = NdkPlugin.CPP_SUFFIXES.any { path.fileName.toString().endsWith(it) }

        for (candidate in candidates) {
            // The engine's graded matcher, not a raw startsWith, so `pb` still finds `push_back`.
            if (!params.prefixMatches(candidate.name)) continue
            result.addElement(candidate.toItem(cpp))
        }
    }

    private suspend fun candidatesFor(
        toolchain: NdkToolchain,
        path: java.nio.file.Path,
        text: String,
        params: CompletionParams,
    ): List<ClangCompletion>? {
        // The same flags the editor's diagnostics use, so the popup and the squiggles see one file: the
        // module's own facet, or on a host that does not hand completion its module, the one diagnostics
        // last recorded for the file's module (see [NdkFlags.remember]). An unseen module gets the defaults.
        val facet = facetOf(params) ?: NdkFlags.facetFor(path) ?: NdkFacet()

        // Keyed on the buffer and the position, NOT the prefix: clang is asked at the start of the word being
        // typed, so every keystroke within one word is the same query and must not re-run the compiler. That
        // means the word itself is cut out of the key -- hashing the whole buffer, as this once did, changed
        // the key on every keystroke and re-ran clang for each one. The facet is part of it, since a
        // different standard offers different members.
        val wordStart = params.replacementRange.start
        val key = listOf(params.document.file.path, keyText(text, params), wordStart, facet.hashCode())
        synchronized(this) { if (cachedKey == key) return cached }

        val (line, column) = LineOffsets(text).lineColOf(wordStart)
        val cpp = NdkPlugin.CPP_SUFFIXES.any { path.fileName.toString().endsWith(it) }

        // Killed with the request: a popup superseded by the next keystroke must not keep its clang running.
        val result = killableTool { cancellation ->
            when (toolchain.prepare()) {
                is NdkToolchain.Status.Unavailable -> null
                is NdkToolchain.Status.Ready ->
                    toolchain.complete(path, text, line, column, wordStart, cpp, NdkFlags.editor(facet, toolchain, cpp), cancellation)
            }
        } ?: return null

        // A completion request that fails is ordinary: the file is mid-edit and does not parse. clang still
        // offers what it could work out, so the output is read either way and only a silent failure is logged.
        val candidates = runCatching { ClangCompletions.parse(result.output) }
            .onFailure { log.warn("could not read clang's completion output", it) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty() && !result.ok) {
            log.warn("clang offered no completions at $line:$column: ${result.output.take(200)}")
        }

        synchronized(this) {
            cachedKey = key
            cached = candidates
        }
        return candidates
    }

    /** The hash of the buffer with the word being typed taken out: the part that decides what clang answers. */
    private fun keyText(text: String, params: CompletionParams): Int {
        val start = params.replacementRange.start.coerceIn(0, text.length)
        val end = maxOf(params.replacementRange.end, params.offset).coerceIn(start, text.length)
        return text.substring(0, start).hashCode() * 31 + text.substring(end).hashCode()
    }

    /**
     * The facet of the module the host says the file belongs to. Null when it belongs to none, carries no
     * facet, or the host predates SPI 3.1.0 and [CompletionParams.module] does not link.
     */
    private fun facetOf(params: CompletionParams): NdkFacet? =
        try {
            params.module?.facets?.get(NdkFacet.KEY)
        } catch (e: LinkageError) {
            null
        }
}

/**
 * Map a candidate onto the popup.
 *
 * clang's text format carries no kind, so it is inferred from the shape it did give: a parameter list means
 * something callable, a pattern is a snippet, and anything else is a value. Inferring wrongly costs an icon,
 * which is why this errs towards the neutral answer rather than guessing at classes and fields.
 */
internal fun ClangCompletion.toItem(cpp: Boolean): CompletionItem = CompletionItem(
    label = name,
    insertText = name,
    kind = kindFromCursor() ?: when {
        isPattern -> CompletionItemKind.SNIPPET
        isCallable -> CompletionItemKind.METHOD
        // No result type and not callable: clang lists macros and type names this way, and in C almost
        // everything with no type is a macro.
        resultType == null -> if (cpp) CompletionItemKind.CLASS else CompletionItemKind.KEYWORD
        else -> CompletionItemKind.VARIABLE
    },
    detail = signature ?: resultType,
    // Where the popup puts the origin. The compiler does not tell us a declaring class in this format, so
    // the result type is the most useful thing to put there.
    container = resultType?.takeIf { signature != null },
)

/**
 * The popup kind for libclang's cursor kind, when the daemon supplied one. Null for a kind with no clear
 * counterpart, which falls back to the shape-based guess.
 */
internal fun ClangCompletion.kindFromCursor(): CompletionItemKind? = when (kind) {
    null -> null
    "FunctionDecl", "FunctionTemplate", "CXXMethod", "ConversionFunction", "OverloadCandidate" -> CompletionItemKind.METHOD
    "Constructor", "Destructor" -> CompletionItemKind.CONSTRUCTOR
    "FieldDecl" -> CompletionItemKind.FIELD
    "VarDecl" -> CompletionItemKind.VARIABLE
    "ParmDecl" -> CompletionItemKind.PARAMETER
    "EnumConstantDecl" -> CompletionItemKind.ENUM_CONSTANT
    "EnumDecl" -> CompletionItemKind.ENUM
    "StructDecl", "UnionDecl", "ClassDecl", "ClassTemplate", "ClassTemplatePartialSpecialization",
    "TypedefDecl", "TypeAliasDecl", "TypeAliasTemplateDecl" -> CompletionItemKind.CLASS
    "Namespace", "NamespaceAlias" -> CompletionItemKind.PACKAGE
    "TemplateTypeParameter", "NonTypeTemplateParameter", "TemplateTemplateParameter" -> CompletionItemKind.TYPE_PARAMETER
    // A function-like macro is called like a function; an object-like one reads as a constant.
    "macrodefinition" -> if (isCallable) CompletionItemKind.METHOD else CompletionItemKind.KEYWORD
    // Keywords and code patterns: a pattern carries placeholders to fill in, a keyword does not.
    "NotImplemented" -> if (signature != null && signature != name) CompletionItemKind.SNIPPET else CompletionItemKind.KEYWORD
    else -> null
}
