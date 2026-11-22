package dev.ide.lang.kotlin.completion

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtTypeConstraint
import dev.ide.kotlin.syntax.psi.KtTypeConstraintList
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.PrefixMatcher
import dev.ide.lang.kotlin.parse.climbTo
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.SymbolKind

/**
 * The positions that name one of a small, fixed set of things, each completed on its own as the IntelliJ Kotlin
 * plugin completes it:
 *  - the qualifier of `super<…>`, the enclosing class's direct supertypes (`K2SuperEntryContributor`);
 *  - the `super<X>` forms of the `super` keyword where the class has several supertypes (`SuperKeywordHandler`);
 *  - the subject of a `where` constraint, the type parameters of the declaration it constrains
 *    (`K2TypeParameterConstraintNameInWhereClauseCompletionContributor`), and nothing after a `.` there.
 * Pure PSI plus one type-name resolution per supertype; nothing here runs at an ordinary name position.
 */
internal object KotlinSuperAndWhereCompletion {

    /**
     * Whether the caret is right after an unclosed `super<` (`super<<caret>`, `super<A<caret>`): IntelliJ's
     * `isInUnclosedSuperQualifier`, which closes the qualifier with a `>` after the dummy identifier so the
     * position parses as one. [tokenStart] is where the identifier being typed starts.
     */
    fun inUnclosedSuperQualifier(text: CharSequence, tokenStart: Int): Boolean {
        var i = tokenStart - 1
        // Back over the qualified name written so far (`super<a.b.<caret>`) and whitespace; a qualifier is short,
        // so the walk gives up well before it could cost anything.
        val stop = maxOf(-1, tokenStart - 1 - MAX_QUALIFIER_SCAN)
        while (i > stop && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.' || text[i].isWhitespace())) i--
        if (i < 0 || text[i] != '<') return false
        i--
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 4 || (0 until 5).any { k -> text[i - 4 + k] != "super"[k] }) return false
        return i - 5 < 0 || !(text[i - 5].isLetterOrDigit() || text[i - 5] == '_')
    }

    private const val MAX_QUALIFIER_SCAN = 256

    /** The class whose direct supertypes a `super` at [leaf] may name, or null outside one. */
    private fun enclosingClass(leaf: KtElement?): KtClassOrObject? = climbTo<KtClassOrObject>(leaf)

    /** The direct supertypes of [cls] that are classes, by the name written and their FQN. */
    private fun superClasses(cls: KtClassOrObject, resolver: KotlinResolver, service: KotlinSymbolService): List<Pair<String, String>> {
        val enclosing = cls.fqName?.asString()
        return cls.superTypeListEntries.mapNotNull { entry ->
            val user = entry.typeReference?.typeElement as? KtUserType ?: return@mapNotNull null
            val name = user.referencedName ?: return@mapNotNull null
            val written = qualifiedText(user) ?: return@mapNotNull null
            val fqn = service.resolveTypeName(written, resolver.fileContext, enclosing)?.takeIf { service.isKnownType(it) }
                ?: return@mapNotNull null
            name to fqn
        }
    }

    /** `a.b.C` for a user type written so, without its type arguments. */
    private fun qualifiedText(user: KtUserType): String? {
        val name = user.referencedName ?: return null
        val q = user.qualifier ?: return name
        return (qualifiedText(q) ?: return null) + "." + name
    }

    /**
     * The items at `super<<caret>`: the direct supertypes of the enclosing class, null when the caret is not in a
     * `super` qualifier. [closeBracket] appends the `>` the qualifier still lacks.
     */
    fun superQualifierItems(
        leaf: KtElement?, matcher: PrefixMatcher, resolver: KotlinResolver, service: KotlinSymbolService, closeBracket: Boolean,
    ): List<CompletionItem>? {
        val ref = climbTo<KtTypeReference>(leaf) ?: return null
        if (ref.parent !is KtSuperExpression) return null
        val cls = enclosingClass(ref) ?: return emptyList()
        return superClasses(cls, resolver, service).mapNotNull { (name, fqn) ->
            if (!matcher.matches(name)) return@mapNotNull null
            val kind = if (service.isInterfaceType(fqn) == true) SymbolKind.INTERFACE else SymbolKind.CLASS
            CompletionItem(
                label = name,
                insertText = if (closeBracket) "$name>" else name,
                kind = if (kind == SymbolKind.INTERFACE) CompletionItemKind.INTERFACE else CompletionItemKind.CLASS,
                detail = fqn,
                symbol = KotlinSymbol(name, kind, service.typeByFqn(fqn)),
            )
        }
    }

    /**
     * The `super<X>` keyword forms beside `super` where the enclosing class has more than one supertype: one
     * per direct supertype, inserted with the `.` that follows, as `SuperKeywordHandler` offers them.
     */
    fun qualifiedSuperKeywords(leaf: KtElement?, prefix: String, resolver: KotlinResolver, service: KotlinSymbolService): List<CompletionItem> {
        val cls = enclosingClass(leaf) ?: return emptyList()
        if (cls.superTypeListEntries.size <= 1 || !"super".startsWith(prefix)) return emptyList()
        val supers = superClasses(cls, resolver, service)
        if (supers.size <= 1) return emptyList()
        return supers.map { (name, _) ->
            CompletionItem("super<$name>", "super<$name>.", CompletionItemKind.KEYWORD, sortPriority = 50)
        }
    }

    /**
     * The items of a `where` clause's constraint subject (`where <caret>`): the type parameters of the class or
     * function the clause belongs to, inserted with ` : `. After a `.` there (`where T.<caret>`) nothing is
     * offered. Null when the caret is elsewhere.
     */
    fun whereClauseItems(leaf: KtElement?, nameRef: KtNameReferenceExpression?, matcher: PrefixMatcher): List<CompletionItem>? {
        val ref = nameRef ?: return null
        val constraint = ref.parent as? KtTypeConstraint
        if (constraint != null && constraint.subjectTypeParameterName === ref) {
            val owner = (constraint.parent as? KtTypeConstraintList)?.parent as? KtTypeParameterListOwner ?: return emptyList()
            return owner.typeParameters.mapNotNull { tp ->
                val name = tp.name ?: return@mapNotNull null
                if (!matcher.matches(name)) return@mapNotNull null
                CompletionItem(name, "$name : ", CompletionItemKind.TYPE_PARAMETER, detail = " : ")
            }
        }
        // `where T.<caret>`: the constraint has no `:`, so what follows the dot is no bound.
        val typeRef = climbTo<KtTypeReference>(leaf) ?: return null
        val owner = typeRef.parent as? KtTypeConstraint ?: return null
        if (owner.boundTypeReference !== typeRef) return null
        val before = owner.text.substring(0, (typeRef.textOffset - owner.textOffset).coerceIn(0, owner.textLength))
        return if (':' in before) null else emptyList()
    }
}
