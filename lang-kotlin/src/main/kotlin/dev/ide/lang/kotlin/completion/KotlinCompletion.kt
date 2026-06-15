package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Kotlin code completion, using the completion-token technique: splice a dummy identifier at
 * the caret on a copy of the buffer, parse it, find the marker's element, and classify the position:
 *   • selector of a (safe-)qualified expression -> MEMBER_ACCESS -> receiver members ∪ extensions
 *   • bare name in expression position        -> NAME_REFERENCE -> scope symbols + visible types
 *   • inside a type reference                  -> TYPE_REFERENCE -> visible classifiers
 * Candidates are prefix-filtered, ranked (prefix/proximity), and mapped to neutral [CompletionItem]s.
 */
class KotlinCompletionService(private val service: KotlinSymbolService) : CompletionService {

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        val original = request.document.text.toString()
        val offset = request.offset.coerceIn(0, original.length)
        val prefix = identifierPrefixBefore(original, offset)
        val replaceRange = TextRange(offset - prefix.length, offset)

        // Splice the marker right at the caret so the parser yields a real reference node even after `.`.
        val spliced = original.substring(0, offset) + MARKER + original.substring(offset)
        val kt = KotlinParserHost.parse(request.document.file.name, spliced)
        val parsed = KotlinParsedFile(kt, request.document.file, request.document.version)
        val resolver = KotlinResolver(kt, parsed, service)

        val markerLeaf = kt.findElementAt(offset)
        // Don't complete inside a string literal's text (but DO inside ${ ... } template entries — that's code).
        if (insideStringLiteral(markerLeaf)) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = replaceRange)
        }
        val nameRef = climbTo<KtNameReferenceExpression>(markerLeaf)

        val raw: List<KotlinSymbol> = when {
            nameRef != null && isSelectorOfQualified(nameRef) -> {
                val qualified = nameRef.parent as KtQualifiedExpression
                val receiver = qualified.receiverExpression
                val recvType = resolver.inferType(receiver)
                if (recvType != null) {
                    // Instance receiver (`listOf("").`) → instance members + extensions; type receiver
                    // (`Int.`) → companion ("static") members + nested. Built-ins now provide the real Kotlin
                    // members + companion (from .kotlin_builtins), so `List.` is naturally empty and `Int.`
                    // shows MAX_VALUE. Constructors are never reached via `.`.
                    val typeReceiver = resolver.isTypeReceiver(receiver)
                    service.membersOf(recvType.qualifiedName, recvType.typeArguments, null)
                        .filterIsInstance<KotlinSymbol>()
                        .filter { memberVisibleOn(it, typeReceiver) }
                } else {
                    emptyList() // unknown receiver -> degrade to nothing rather than a wrong set
                }
            }
            inTypePosition(markerLeaf) -> service.typeNamesByPrefix(prefix)
            else -> resolver.scopeSymbolsAt(offset) + service.typeNamesByPrefix(prefix)
        }

        // A type used by simple name needs an `import` unless it's already visible (same package, a default
        // import, or already imported). That import is attached as an additionalEdit and in-scope candidates
        // rank first, so the popup reflects what is visible and accepting an unimported type auto-imports it.
        val visiblePackages = (DefaultImports.STAR_PACKAGES +
            resolver.fileContext.packageName +
            resolver.fileContext.imports.filter { it.isStar }.map { it.packageName }).toHashSet()
        val explicitImports = resolver.fileContext.imports.filter { !it.isStar }.map { it.fqn }.toHashSet()
        val anchor = importAnchorOf(kt)

        fun importEditFor(s: KotlinSymbol): List<TextEdit> {
            val fqn = when {
                s.kind in TYPE_KINDS -> (s.type as? KotlinType)?.qualifiedName
                // A top-level callable (println, listOf, ln, …) is identified by its declaring package.
                // `ln`/`PI`/`E` from kotlin.math aren't default-imported, so they need an import on accept.
                s.packageName != null && !s.isExtension && (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) ->
                    "${s.packageName}.${s.name}"
                else -> null
            } ?: return emptyList()
            if ('.' !in fqn) return emptyList()
            val pkg = fqn.substringBeforeLast('.')
            if (pkg in visiblePackages || fqn in explicitImports) return emptyList()
            return listOf(TextEdit(TextRange(anchor.offset, anchor.offset), anchor.prefix + "import " + fqn + anchor.suffix))
        }

        val candidates = raw.asSequence()
            .filter { it.name != "_" && it.name.startsWith(prefix, ignoreCase = true) && MARKER !in it.name }
            .distinctBy { it.name + "#" + it.kind + "#" + (it.signature ?: "") }
            .map { Candidate(it, importEditFor(it)) }
            .sortedWith(rank(prefix))
            .take(MAX_ITEMS)
            .toList()

        val items = candidates.map { toItem(it.symbol, it.importEdit) }
        return CompletionResult(items = items, isIncomplete = raw.size > MAX_ITEMS, replacementRange = replaceRange)
    }

    private class Candidate(val symbol: KotlinSymbol, val importEdit: List<TextEdit>)

    private fun rank(prefix: String): Comparator<Candidate> = compareBy(
        { if (it.symbol.name.startsWith(prefix)) 0 else 1 },       // case-sensitive prefix first
        { if (it.importEdit.isEmpty()) 0 else 1 },                 // in-scope (no import) before needs-import
        { proximity(it.symbol.kind) },                            // locals/members before library
        { it.symbol.name.length },
        { it.symbol.name },
    )

    private fun proximity(kind: SymbolKind): Int = when (kind) {
        SymbolKind.LOCAL_VARIABLE, SymbolKind.PARAMETER -> 0
        SymbolKind.FIELD, SymbolKind.METHOD -> 1
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM -> 3
        else -> 2
    }

    private fun toItem(s: KotlinSymbol, importEdit: List<TextEdit>): CompletionItem {
        val isFunction = s.kind == SymbolKind.METHOD || s.kind == SymbolKind.CONSTRUCTOR
        val hasParams = isFunction && s.signature?.startsWith("()") == false && s.signature?.startsWith("(") == true
        val insert = if (isFunction) "${s.name}()" else s.name
        val caret = when {
            isFunction && hasParams -> CaretAction.At(s.name.length + 1) // between the parens
            else -> CaretAction.AtEnd
        }
        // Read like Kotlin source: the label is `println(message: String)` (name + params adjacent), and the
        // return/value type is the grayed detail on the right (`Unit`).
        val label = if (isFunction) s.name + paramListOf(s.signature) else s.name
        val detail = buildString {
            s.type?.let { append(typeLabel(it)) } // return type (funcs) / declared type (vals, props)
            if (s.isExtension) append(if (isEmpty()) "(extension)" else "  (extension)")
            if (importEdit.isNotEmpty()) {
                (s.type as? KotlinType)?.qualifiedName?.substringBeforeLast('.')
                    ?.let { append(if (isEmpty()) "($it)" else "  ($it)") }
            }
        }.ifBlank { null }
        return CompletionItem(
            label = label,
            insertText = insert,
            kind = itemKind(s.kind),
            detail = detail,
            sortPriority = proximity(s.kind),
            symbol = s,
            additionalEdits = importEdit,
            caret = caret,
        )
    }

    /** Extract the parameter list `(message: String)` from a `(message: String): Unit` signature. */
    private fun paramListOf(sig: String?): String {
        if (sig.isNullOrEmpty()) return "()"
        val i = sig.lastIndexOf("): ")
        return when {
            i >= 0 -> sig.substring(0, i + 1)
            sig.startsWith("(") -> sig // a constructor's "(params)" with no return
            else -> "()"
        }
    }

    /** Where to splice a new `import`: after the last import, else after the package, else at the top. */
    private data class ImportAnchor(val offset: Int, val prefix: String, val suffix: String)

    private fun importAnchorOf(kt: KtFile): ImportAnchor {
        kt.importDirectives.lastOrNull()?.let { return ImportAnchor(it.textRange.endOffset, "\n", "") }
        val pkg = kt.packageDirective
        if (pkg != null && pkg.textLength > 0 && pkg.text.isNotBlank()) {
            return ImportAnchor(pkg.textRange.endOffset, "\n\n", "")
        }
        return ImportAnchor(0, "", "\n\n")
    }

    /** A short type label for the popup: `List<String>?`, `Greeter`, … (simple name + args + nullability). */
    private fun typeLabel(type: TypeRef): String =
        (type as? KotlinType)?.toString() ?: type.qualifiedName.substringAfterLast('.')

    private fun itemKind(kind: SymbolKind): CompletionItemKind = when (kind) {
        SymbolKind.METHOD -> CompletionItemKind.METHOD
        SymbolKind.CONSTRUCTOR -> CompletionItemKind.CONSTRUCTOR
        SymbolKind.FIELD -> CompletionItemKind.FIELD
        SymbolKind.LOCAL_VARIABLE -> CompletionItemKind.VARIABLE
        SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
        SymbolKind.CLASS -> CompletionItemKind.CLASS
        SymbolKind.INTERFACE -> CompletionItemKind.INTERFACE
        SymbolKind.ENUM -> CompletionItemKind.ENUM
        SymbolKind.ENUM_CONSTANT -> CompletionItemKind.ENUM_CONSTANT
        SymbolKind.ANNOTATION_TYPE -> CompletionItemKind.ANNOTATION_TYPE
        SymbolKind.RECORD -> CompletionItemKind.RECORD
        SymbolKind.PACKAGE -> CompletionItemKind.PACKAGE
        SymbolKind.TYPE_PARAMETER -> CompletionItemKind.TYPE_PARAMETER
    }

    /** Instance receiver → non-static members (+ extensions); type receiver → statics + nested types. */
    private fun memberVisibleOn(s: KotlinSymbol, typeReceiver: Boolean): Boolean {
        if (s.kind == SymbolKind.CONSTRUCTOR) return false // never reached via `.`
        if (Modifier.PRIVATE in s.modifiers) return false // private members aren't accessible via an explicit `.`
        if (s.isInternal && !s.origin.fromSource) return false // a library's `internal` isn't accessible cross-module
        val isStatic = Modifier.STATIC in s.modifiers
        return if (typeReceiver) isStatic || s.kind in TYPE_KINDS else !isStatic
    }

    /** Inside the literal text of a string (suppress completion), but NOT inside a `${ }`/`$name` entry. */
    private fun insideStringLiteral(leaf: PsiElement?): Boolean {
        climbTo<KtStringTemplateExpression>(leaf) ?: return false
        val entry = climbTo<KtStringTemplateEntry>(leaf)
        return !(entry is KtBlockStringTemplateEntry || entry is KtSimpleNameStringTemplateEntry)
    }

    private fun isSelectorOfQualified(nameRef: KtNameReferenceExpression): Boolean {
        val q = nameRef.parent as? KtQualifiedExpression ?: return false
        return q.selectorExpression === nameRef
    }

    private fun inTypePosition(leaf: PsiElement?): Boolean = climbTo<KtTypeReference>(leaf) != null

    private inline fun <reified T> climbTo(start: PsiElement?): T? {
        var n = start
        while (n != null) {
            if (n is T) return n
            n = n.parent
        }
        return null
    }

    private fun identifierPrefixBefore(text: String, offset: Int): String {
        var i = offset
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
        return text.substring(i, offset)
    }

    private companion object {
        // The classic completion dummy identifier; unlikely to collide with real code.
        const val MARKER = "IntellijIdeaRulezzz"
        const val MAX_ITEMS = 100
        val TYPE_KINDS = setOf(
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.ANNOTATION_TYPE, SymbolKind.RECORD,
        )
    }
}
