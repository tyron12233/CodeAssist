package dev.codeassist.csharp.completion


import android.util.Log
import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojGraph
import dev.codeassist.csharp.project.csproj.CsprojInfo
import dev.codeassist.csharp.util.TAG
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Completes what follows a dot: `Foo.` lists the namespace's types, `Foo.Greeter.` lists the static members of
 * that type, and `greeter.` lists the instance members of the type `greeter` was declared with. Only symbols of
 * the project's referenced DLLs and of its own sources are known, as [CsSymbols] reports them.
 *
 * Works on the text before the caret, not on a syntax tree: the backend has none. A chain like `a.b.c.` is
 * taken from the characters before the dot, so an expression such as `list[0].` is not completed.
 */
internal class CsCompletionContributor(
    private val symbols: CsSymbols,
    private val semantic: CsSemanticCompletion,
) : CompletionContributor {

    override val id = "csharp.symbols"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val text = params.document.text
        val caret = params.replacementRange.start.coerceIn(0, text.length)
        val before = text.subSequence(lineStartBefore(text, caret), caret).toString()
        when {
            before.endsWith('.') -> {
                // Roslyn knows the receiver's real type (a local, a call's result, an inherited member); the symbol
                // table is the fallback for when it cannot answer or has nothing to say.
                val items = semanticItems(params)
                if (!items.isNullOrEmpty()) {
                    result.stopHere()
                    addSemantic(params, result, items)
                } else {
                    completeAfterDot(params, result, text, before)
                }
            }
            USING_START.matches(before) -> completeNamespaceNames(params, result)
            // An empty prefix would offer every type of the base class library on each keystroke.
            params.prefix.isNotEmpty() -> {
                semanticItems(params)?.let { addSemantic(params, result, it) }
                completeTypeNames(params, result, text)
                completeKeywordsAndNames(params, result, text)
            }
        }
    }

    /** Roslyn's candidates, or null when it could not answer. A failure is logged and never reaches the editor. */
    private suspend fun semanticItems(params: CompletionParams): List<CsCompletionItems.Item>? = try {
        semantic.itemsFor(params)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "completion: the semantic model could not answer: $e")
        null
    }

    private fun addSemantic(
        params: CompletionParams,
        result: CompletionResultSet,
        items: List<CsCompletionItems.Item>,
    ) {
        for (item in items) {
            if (!params.prefixMatches(item.name)) continue
            val kind = when (item.kind) {
                "class", "struct", "delegate" -> CompletionItemKind.CLASS
                "interface" -> CompletionItemKind.INTERFACE
                "enum" -> CompletionItemKind.ENUM
                "method", "extension" -> CompletionItemKind.METHOD
                "enummember" -> CompletionItemKind.ENUM_CONSTANT
                "property", "field", "constant", "event" -> CompletionItemKind.FIELD
                "parameter" -> CompletionItemKind.PARAMETER
                "typeparameter" -> CompletionItemKind.TYPE_PARAMETER
                "namespace" -> CompletionItemKind.PACKAGE
                else -> CompletionItemKind.VARIABLE
            }
            val overloads = if (item.overloads > 1) " (+${item.overloads - 1} overloads)" else ""
            val container = if (item.kind == "extension") "${item.container} (extension)" else item.container
            // A method is accepted as `name()`, with the caret between the parentheses when every overload takes
            // arguments.
            val call = item.isMethod
            result.addElement(
                CompletionItem(
                    label = item.name,
                    insertText = if (call) item.name + "()" else item.name,
                    kind = kind,
                    detail = (item.detail + overloads).ifEmpty { null },
                    container = container.ifEmpty { null },
                    documentation = CsDocText.summary(item.documentation),
                    caret = if (call && item.takesArguments) {
                        CaretAction.At(item.name.length + 1)
                    } else {
                        CaretAction.AtEnd
                    },
                ),
            )
        }
    }

    /** `Foo.`: what can follow a dot after the chain of names before it. */
    private suspend fun completeAfterDot(
        params: CompletionParams,
        result: CompletionResultSet,
        text: CharSequence,
        before: String,
    ) {
        val chain = before.dropLast(1).takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        if (chain.isEmpty() || chain.startsWith('.') || chain.first().isDigit()) return
        // After a dot only members make sense, so the keywords are not offered here, resolved or not.
        result.stopHere()

        val table = loadSymbols(params)?.table ?: return
        addStaticMembers(params, result, table, chain)
        if (!chain.contains('.')) addInstanceMembers(params, result, table, text, chain)
        addNamespaceContents(params, result, table, chain)
    }

    /** `using Sy`: the first segment of every namespace. Longer paths are completed after their dots. */
    private suspend fun completeNamespaceNames(params: CompletionParams, result: CompletionResultSet) {
        val table = loadSymbols(params)?.table ?: return
        for (namespace in topLevelNamespaces(table)) addVariant(params, result, namespace, "namespace")
    }

    /**
     * `Con`: the types of the namespaces the file can see, and the first segment of every namespace, for a
     * name written in full such as `System.Console`. The keywords stay, so [CompletionResultSet.stopHere] is not
     * called. A text match on the `using` lines: it does not know scope, comments or strings.
     */
    private suspend fun completeTypeNames(params: CompletionParams, result: CompletionResultSet, text: CharSequence) {
        // The symbol tool failing must not take the keywords and the file's own names with it.
        var read: Loaded? = null
        try {
            read = loadSymbols(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "completion: symbols could not be read: $e")
        }
        val loaded = read ?: return
        val visible = visibleNamespaces(text, loaded.info)
        // What the semantic model already offered is not offered twice.
        val offered = result.elements.mapTo(HashSet()) { it.label }
        for (type in loaded.table.types) {
            if (type.namespace in visible && type.name !in offered) addVariant(params, result, type.name, type.kind)
        }
        for (namespace in topLevelNamespaces(loaded.table)) {
            if (namespace !in offered) addVariant(params, result, namespace, "namespace")
        }
    }

    /**
     * The keywords, and the names the file already uses (a local written above, a parameter, a method). The host
     * has no keyword or word source for C#: each language backend publishes its own, and [CsLanguageBackend] has none.
     * A keyword is a fallback below real symbols (a positive priority); a name that is declared in the file is a
     * variable, any other word of the file comes last. Names a symbol already offered are not repeated.
     */
    private fun completeKeywordsAndNames(params: CompletionParams, result: CompletionResultSet, text: CharSequence) {
        val offered = result.elements.mapTo(HashSet()) { it.label }
        for (keyword in CsKeywords.ALL) {
            if (keyword in offered || !params.prefixMatches(keyword)) continue
            result.addElement(
                CompletionItem(
                    label = keyword, insertText = keyword, kind = CompletionItemKind.KEYWORD,
                    detail = "keyword", sortPriority = 1,
                ),
            )
        }
        for (word in CsWords.nearby(text.toString(), params.offset, { it !in offered && params.prefixMatches(it) })) {
            result.addElement(
                CompletionItem(
                    label = word.name, insertText = word.name,
                    kind = if (word.declared) CompletionItemKind.VARIABLE else CompletionItemKind.WORD,
                    detail = if (word.declared) "in this file" else null,
                ),
            )
        }
    }

    /** The symbols of the module the file belongs to, or null when no module is found. */
    private suspend fun loadSymbols(params: CompletionParams): Loaded? {
        // The module the host says the file belongs to; the search upwards only when it says none.
        val moduleDir = params.module?.let { Paths.get(it.dir.path) }
            ?: moduleDirOf(Paths.get(params.document.file.path))
            ?: return null
        // Null for a module with no `.csproj`: its own sources are all there is to read, as in the editor's check.
        val info = Csproj.load(moduleDir)
        val sources = CsprojGraph.sources(moduleDir, info)
        return Loaded(info, symbols.tableFor(info, moduleDir, sources))
    }

    private class Loaded(val info: CsprojInfo?, val table: CsSymbolTable)

    /**
     * The namespaces whose types can be written without a prefix: the global one (`-`), those the file names in
     * `using` and `namespace` lines, and the implicit usings when the project has them.
     */
    private fun visibleNamespaces(text: CharSequence, info: CsprojInfo?): Set<String> {
        val visible = hashSetOf("-")
        NAMESPACE_LINE.findAll(text).forEach { visible += it.groupValues[1] }
        IMPLICIT_USING.findAll(Csproj.globalUsingsText(info)).forEach { visible += it.groupValues[1] }
        return visible
    }

    private fun topLevelNamespaces(table: CsSymbolTable): List<String> =
        table.types.map { it.namespace }
            .filter { it != "-" }
            .map { it.substringBefore('.') }
            .distinct()

    /** The offset where the line holding [offset] starts. */
    private fun lineStartBefore(text: CharSequence, offset: Int): Int {
        val newline = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
        return if (newline < 0 || offset == 0) 0 else newline + 1
    }

    /** `Foo.Greeter.`: the static members of a type named by [chain], in full or, with no dot, by its short name. */
    private fun addStaticMembers(
        params: CompletionParams,
        result: CompletionResultSet,
        table: CsSymbolTable,
        chain: String,
    ) {
        for (type in table.types) {
            val named = type.fullName == chain || (!chain.contains('.') && type.name == chain)
            if (!named) continue
            for (member in table.members) {
                if (member.typeFullName != type.fullName || !member.isStatic) continue
                addVariant(params, result, member.name, member.kind, member.paramCount)
            }
        }
    }

    /** `greeter.`: the instance members of the declared types of the variable [variable]. */
    private fun addInstanceMembers(
        params: CompletionParams,
        result: CompletionResultSet,
        table: CsSymbolTable,
        text: CharSequence,
        variable: String,
    ) {
        for (typeName in declaredTypesOf(text, variable)) {
            for (type in table.types) {
                if (type.name != typeName) continue
                for (member in table.members) {
                    if (member.typeFullName != type.fullName || member.isStatic) continue
                    addVariant(params, result, member.name, member.kind, member.paramCount)
                }
            }
        }
    }

    /** `Foo.`: the types of the namespace [chain], and the namespaces directly inside it. */
    private fun addNamespaceContents(
        params: CompletionParams,
        result: CompletionResultSet,
        table: CsSymbolTable,
        chain: String,
    ) {
        for (type in table.types) {
            if (type.namespace != chain) continue
            addVariant(params, result, type.name, type.kind)
        }
        val childNamespaces = table.types.map { it.namespace }
            .filter { it.startsWith("$chain.") }
            .map { it.removePrefix("$chain.").substringBefore('.') }
            .distinct()
        for (child in childNamespaces) addVariant(params, result, child, "namespace")
    }

    /**
     * The type names a variable is declared with in this file: `Greeter g =` or `var g = new Greeter(`.
     * A text match, not a resolver result: it ignores scope, so two variables with one name in different
     * methods both count, and a field or parameter declared in another file is not found.
     */
    private fun declaredTypesOf(text: CharSequence, variable: String): List<String> {
        val name = Regex.escape(variable)
        val declared = Regex("""(?<![\w.])([A-Z]\w*)(?:<[^;=()]*>)?\s+$name\s*[=;,)]""")
            .findAll(text).map { it.groupValues[1] }
        val created = Regex("""\bvar\s+$name\s*=\s*new\s+([A-Z]\w*)""")
            .findAll(text).map { it.groupValues[1] }
        return (declared + created).distinct().toList()
    }

    /** Offers [name] when it matches what is typed; [kind] is the tool's kind word, which picks the icon. */
    private fun addVariant(
        params: CompletionParams,
        result: CompletionResultSet,
        name: String,
        kind: String,
        paramCount: Int = 0,
    ) {
        if (!params.prefixMatches(name)) return
        val itemKind = when (kind) {
            "class" -> CompletionItemKind.CLASS
            "interface" -> CompletionItemKind.INTERFACE
            "method" -> CompletionItemKind.METHOD
            "field" -> CompletionItemKind.FIELD
            "property" -> CompletionItemKind.FIELD
            "namespace" -> CompletionItemKind.PACKAGE
            else -> CompletionItemKind.VARIABLE
        }
        // A method is accepted as `name()`, with the caret between the parentheses when it takes arguments.
        if (kind == "method") {
            val caret = if (paramCount > 0) CaretAction.At(name.length + 1) else CaretAction.AtEnd
            result.addElement(
                CompletionItem(label = name, insertText = "$name()", kind = itemKind, detail = kind, caret = caret),
            )
            return
        }
        result.addElement(CompletionItem(label = name, insertText = name, kind = itemKind, detail = kind))
    }

    /** The folder with the `.csproj`, looked for from the file upwards, for when the host names no module. */
    private fun moduleDirOf(file: Path): Path? {
        var dir: Path? = file.parent
        var depth = 0
        while (dir != null && depth < 4) {
            if (Csproj.find(dir) != null) return dir
            dir = dir.parent
            depth++
        }
        return null
    }

    private companion object {
        /** A line that has only `using` before the name: `using `, `global using `. */
        val USING_START = Regex("""^\s*(?:global\s+)?using\s+$""")

        /** `using X;`, `namespace X;` and `namespace X {`: the namespace a file names. */
        val NAMESPACE_LINE = Regex("""(?m)^\s*(?:using|namespace)\s+([\w.]+)\s*[;{]?\s*$""")

        /** A plain line of [Csproj.globalUsingsText]; an alias or static one names no namespace to list. */
        val IMPLICIT_USING = Regex("""global using global::([\w.]+);""")
    }
}

/** Registers the contributor for the C# language. */
internal fun csCompletionContribution(symbols: CsSymbols, semantic: CsSemanticCompletion): CompletionContribution =
    CompletionContribution(
        contributor = CsCompletionContributor(symbols, semantic),
        languages = setOf(LanguageId(CSharpPlugin.LANGUAGE)),
    )
