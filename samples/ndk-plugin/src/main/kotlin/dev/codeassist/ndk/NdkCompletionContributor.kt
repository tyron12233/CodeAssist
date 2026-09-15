package dev.codeassist.ndk

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.platform.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * C and C++ completion, answered by the compiler.
 *
 * clang has a mode for exactly this (`-code-completion-at`), so the candidates come from the same frontend
 * that compiles the file: members of the real type behind a `.`, macros the real headers define, overloads
 * with their real parameters. That is the whole reason this plugin ships no language server — a second
 * frontend would be another 25 MB and another thing to disagree with the first.
 *
 * What it costs is a parse per request. The engine debounces, and this caches on the exact buffer and offset,
 * so a popup that is re-opened or re-filtered at the same place is free.
 */
class NdkCompletionContributor(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : CompletionContributor {

    override val id = "ndk.clang"

    private var cachedKey: Triple<String, Int, Int>? = null
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
        // Keyed on the buffer and the position, NOT the prefix: clang is asked at the start of the word being
        // typed, so every keystroke within one word is the same query and must not re-run the compiler.
        val wordStart = params.replacementRange.start
        val key = Triple(params.document.file.path, text.hashCode(), wordStart)
        synchronized(this) { if (cachedKey == key) return cached }

        val (line, column) = LineOffsets(text).lineColOf(wordStart)
        val cpp = NdkPlugin.CPP_SUFFIXES.any { path.fileName.toString().endsWith(it) }

        // The same flags the editor's diagnostics use, so the popup and the squiggles see one file. They
        // come from a lookup rather than from the module, because `CompletionParams` carries no `Module`
        // (see [NdkFlags.remember]); an unseen module falls back to the defaults.
        val facet = NdkFlags.facetFor(path) ?: NdkFacet()

        val result = withContext(Dispatchers.IO) {
            when (toolchain.prepare()) {
                is NdkToolchain.Status.Unavailable -> null
                is NdkToolchain.Status.Ready ->
                    toolchain.complete(path, text, line, column, cpp, NdkFlags.editor(facet, toolchain, cpp))
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
    kind = when {
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
