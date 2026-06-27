package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef

/**
 * Renders resolved [KotlinSymbol]s (and the override-stub / named-argument extras) into neutral
 * [CompletionItem]s for the popup. Pure: every function is a value of its arguments, with no symbol-model or
 * resolver state, so it sits apart from the candidate-gathering and ranking in [KotlinCompletion].
 */
internal object KotlinCompletionItems {

    /**
     * A resolved symbol as a popup item: a source-like label (`println(message: String)`), the grayed
     * return/value type as detail, the declaring package/class as origin, doc, the auto-import [importEdit],
     * and a smart insert (call parens / trailing lambda) with caret placement. [followingChar] is the first
     * non-space character after the token the editor will replace, so an item won't add a second `(`/`{` when
     * the call syntax is already there (`foo|(x)` must not become `foo()(x)`).
     */
    fun toItem(s: KotlinSymbol, importEdit: List<TextEdit>, followingChar: Char? = null): CompletionItem {
        val isFunction = s.kind == SymbolKind.METHOD || s.kind == SymbolKind.CONSTRUCTOR
        val hasParams = isFunction && s.signature?.startsWith("()") == false && s.signature.startsWith("(")
        // The call syntax may already be present after the caret (`foo|(x)`, `Column| { }`) — don't add a second
        // `(`/`{`; just insert the name and let the existing arguments/lambda stand.
        val callSyntaxFollows = followingChar == '(' || followingChar == '{'
        val trailingLambda = if (isFunction && !callSyntaxFollows) trailingLambdaParam(s) else null
        val (insert, caret) = when {
            // A function whose LAST parameter is a function type → insert a trailing lambda. Only a SOLE
            // parameter (`remember { }`, `forEach { }`, or a Composable whose only slot is the content lambda)
            // drops the parens and lands the caret in the braces. Any other parameters — even on a @Composable
            // like `Column(modifier, ...) { }` — keep `()` and put the caret inside them so those args are fillable.
            trailingLambda != null -> {
                val lambdaOnly = s.paramTypes.size == 1
                if (lambdaOnly) "${s.name} { }" to CaretAction.At(s.name.length + 2)      // `name { | }`
                else "${s.name}() { }" to CaretAction.At(s.name.length + 1)               // `name(|) { }`
            }
            isFunction && callSyntaxFollows -> s.name to CaretAction.AtEnd                 // parens/braces already there
            isFunction && hasParams -> "${s.name}()" to CaretAction.At(s.name.length + 1)  // between the parens
            isFunction -> "${s.name}()" to CaretAction.AtEnd                               // no-arg call
            else -> s.name to CaretAction.AtEnd
        }
        // Read like Kotlin source: the label is `println(message: String)` (name + params adjacent), and the
        // return/value type is the grayed detail on the right (`Unit`).
        val label = if (isFunction) s.name + paramListOf(s.signature) else s.name
        val detail = buildString {
            s.type?.let { append(typeLabel(it)) } // return type (funcs) / declared type (vals, props)
            if (s.isExtension) append(if (isEmpty()) "(extension)" else "  (extension)")
        }.ifBlank { null }
        // Right-aligned origin: a top-level callable's package, else its declaring class (skip the synthetic
        // `…Kt` file facade — that's an implementation detail, not a place the user thinks of the symbol living).
        val container = s.packageName ?: s.declaringClassFqn?.takeUnless { it.endsWith("Kt") }
        return CompletionItem(
            label = label,
            insertText = insert,
            kind = itemKind(s.kind),
            detail = detail,
            container = container,
            documentation = s.documentation(), // javadoc/KDoc from attached sources → the popup's doc panel
            sortPriority = proximity(s.kind),
            symbol = s,
            additionalEdits = importEdit,
            caret = caret,
        )
    }

    /** An `override fun/val …` stub for an inherited member, body `TODO(...)`, with the caret selecting the TODO. */
    fun overrideItem(m: KotlinSymbol): CompletionItem {
        val isFun = m.kind == SymbolKind.METHOD
        val ret = (m.type as? KotlinType)?.toString()?.takeIf { it.isNotBlank() && it != "Unit" }
        val header =
            if (isFun) "override fun ${m.name}${paramListOf(m.signature)}" + (ret?.let { ": $it" } ?: "")
            else "override val ${m.name}" + (ret?.let { ": $it" } ?: "")
        val todo = "TODO(\"Not yet implemented\")"
        val insert = if (isFun) "$header {\n    $todo\n}" else "$header\n    get() = $todo"
        val sel = insert.indexOf(todo)
        return CompletionItem(
            label = header,
            insertText = insert,
            kind = if (isFun) CompletionItemKind.METHOD else CompletionItemKind.FIELD,
            detail = "override",
            sortPriority = -2,
            symbol = m,
            caret = if (sel >= 0) CaretAction.Select(sel, todo.length) else CaretAction.AtEnd,
        )
    }

    /** A named-argument label. [bareName] inserts only the parameter name (the caret is on an already-named
     *  argument, so ` = ` is present and must not be duplicated); otherwise it inserts `name = `. */
    fun namedArgItem(p: KotlinResolver.ParamInfo, bareName: Boolean = false): CompletionItem = CompletionItem(
        label = "${p.name} =",
        insertText = if (bareName) p.name else "${p.name} = ",
        kind = CompletionItemKind.PARAMETER,
        detail = p.type?.let { typeLabel(it) },
        sortPriority = -1,
    )

    /** Ranking/sort weight by symbol kind: locals and parameters first, then members, then library types. */
    fun proximity(kind: SymbolKind): Int = when (kind) {
        SymbolKind.LOCAL_VARIABLE, SymbolKind.PARAMETER -> 0
        SymbolKind.FIELD, SymbolKind.METHOD -> 1
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM -> 3
        else -> 2
    }

    /** The callee's LAST value parameter when it is a (non-vararg) function type — the slot a trailing lambda
     *  fills (`Column`'s `@Composable () -> Unit` content, `forEach`'s `(T) -> Unit`). Null otherwise, so only
     *  genuinely lambda-taking calls get the `{ }` insert. */
    private fun trailingLambdaParam(s: KotlinSymbol): KotlinType? {
        if (s.paramTypes.isEmpty()) return null
        if (s.varargParamIndex >= 0 && s.varargParamIndex == s.paramTypes.lastIndex) return null // last is vararg, not a lambda
        val last = s.paramTypes.last() as? KotlinType ?: return null
        return last.takeIf { TypeRendering.isFunctionType(it.qualifiedName) }
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
}
