package dev.ide.lang.java.completion

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiSwitchBlock
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.TypeConversionUtil
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionRelevance
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.JavaImportEdits
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.resolve.JavaScope
import dev.ide.lang.java.resolve.JavaSymbol
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind

/**
 * Java code completion, using the completion-marker (dummy-identifier) technique: splice a dummy identifier at
 * the caret on a copy of the buffer, parse it in the resolution [env], find the marker's reference element, and
 * classify the position:
 *   • `expr.|`      -> instance member access  -> the receiver type's accessible members (IntelliJ supertype walk)
 *   • `Type.|`      -> static member access     -> static members + nested types
 *   • `pkg.|`       -> package member access    -> sub-packages + package classes
 *   • bare `foo|`   -> name reference           -> lexical scope + visible types
 *   • type position -> type reference           -> visible types
 *
 * Every semantic answer (member set, accessibility, receiver type) delegates to IntelliJ PSI, so completion
 * inherits the same resolution the rest of the backend uses. Unimported-type (auto-import) completion is
 * index-backed and lands when the index is injected during ide-core wiring.
 */
class JavaCompletion(
    private val env: JavaEnvironment,
    /** Index-backed type search: simple-name prefix → candidate types (FQN + kind), for unimported-type /
     *  auto-import completion. Supplied by the host from the workspace `java.classNames` index; the default
     *  (empty) keeps completion working index-free (only in-scope + java.lang bulk types are then offered). */
    private val typeSearch: (String) -> List<IndexedType> = { emptyList() },
    /** Inheritor search: a supertype FQN → its (transitive) subtypes (FQN + kind) from the workspace subtype
     *  index. Supplied by the host; drives `new <caret>` in an `Foo x = new …` position to offer Foo's
     *  concrete implementations. Empty default keeps completion working index-free. */
    private val subtypeSearch: (String) -> List<IndexedType> = { emptyList() },
) : CompletionContributor {

    /** A type the index knows about: its fully-qualified name and declaration kind (class/interface/…). */
    data class IndexedType(val fqn: String, val kind: String)

    override val id = "java.completion"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val text = params.document.text
        val rr = params.replacementRange
        val markerOffset = rr.start.coerceIn(0, text.length)
        val end = rr.end.coerceIn(markerOffset, text.length)
        val spliced = buildString {
            append(text, 0, markerOffset)
            append(DUMMY)
            append(text, end, text.length)
        }
        val psi = env.parse(params.document.file.name, spliced)
        val leaf = psi.findElementAt(markerOffset) ?: return

        // Declaration-name position (`Foo <caret>` / `Foo f` being retyped): the marker is a variable NAME, not
        // a reference — suggest names derived from the type instead of scope/type candidates.
        val nameVar = (leaf.parent as? PsiVariable)?.takeIf { it.nameIdentifier === leaf }
        if (nameVar != null) {
            fillNameSuggestions(nameVar, params, result)
            result.stopHere()
            return
        }

        val ref = PsiTreeUtil.getParentOfType(leaf, PsiJavaCodeReferenceElement::class.java, false)
        // The type the context expects at the caret (initializer / return / assignment / argument), for
        // smart-completion ranking — computed on THIS spliced tree so it reflects the live buffer.
        val expected = runCatching { expectedTypeAt(leaf) }.getOrNull()
        // What kind of type is legal here (extends → non-final class, implements → interface, `new`/throws/catch
        // → instantiable/Throwable). Only the type-reference branches consult it.
        val ctx = ref?.let { typeContext(it) } ?: TypeCtx.ANY

        when {
            ref is PsiReferenceExpression && ref.qualifierExpression != null ->
                fillMemberAccess(ref.qualifierExpression!!, ref, params, result, expected)

            ref is PsiReferenceExpression -> {
                // `case |` on an enum switch: float the selector enum's constants to the top (still allow the
                // ordinary name reference, since a constant expression is also legal there).
                addSwitchCaseCompletions(leaf, params, result)
                fillNameReference(leaf, markerOffset, psi, params, result)
            }

            ref != null && ref.qualifier is PsiJavaCodeReferenceElement ->
                fillQualifiedTypeReference(ref.qualifier as PsiJavaCodeReferenceElement, ref, params, result, ctx)

            ref != null -> {
                fillTypeReference(psi, params, result, ctx, leaf)
                // A type-ref at a class-body MEMBER position is also where you'd start an override — offer the
                // overridable superclass methods as full stubs (`toStr` → `@Override … toString() { … }`).
                if (ctx == TypeCtx.ANY && isMemberPosition(leaf)) {
                    addOverrideCompletions(leaf, params.replacementRange.start, params, result)
                }
                // `Foo x = new <caret>` — offer Foo's concrete implementations from the subtype index.
                if (ctx == TypeCtx.INSTANTIABLE && expected != null) {
                    addSubtypeCompletions(expected, psi, params, result)
                }
            }

            else ->
                fillNameReference(leaf, markerOffset, psi, params, result)
        }

        // Smart ranking: flag every candidate whose produced type is assignable to the expected type; the
        // engine's ExpectedTypeWeigher floats those to the top (a boost, not a filter).
        if (expected != null) result.replaceAll { boostIfFits(it, expected) }
    }

    // --- smart completion: expected type + name suggestions -----------------------------------------------

    /** The type the position at [leaf] expects: a variable initializer's declared type, the enclosing method's
     *  return type at a `return`, an assignment LHS type, or a call/`new` argument's parameter type. */
    private fun expectedTypeAt(leaf: PsiElement): PsiType? {
        PsiTreeUtil.getParentOfType(leaf, PsiVariable::class.java, false)?.let { v ->
            if (isWithin(leaf, v.initializer)) return v.type
        }
        PsiTreeUtil.getParentOfType(leaf, PsiReturnStatement::class.java, false)?.let { ret ->
            (PsiTreeUtil.getParentOfType(ret, PsiMethod::class.java, PsiLambdaExpression::class.java) as? PsiMethod)
                ?.returnType?.let { if (!it.equalsToText("void")) return it }
        }
        PsiTreeUtil.getParentOfType(leaf, PsiAssignmentExpression::class.java, false)?.let { asg ->
            if (isWithin(leaf, asg.rExpression)) return asg.lExpression.type
        }
        PsiTreeUtil.getParentOfType(leaf, PsiExpressionList::class.java, false)?.let { args ->
            val idx = args.expressions.indexOfFirst { PsiTreeUtil.isAncestor(it, leaf, false) }
            if (idx >= 0) {
                val params = when (val call = args.parent) {
                    is PsiMethodCallExpression -> call.resolveMethod()?.parameterList?.parameters
                    is PsiNewExpression -> call.resolveConstructor()?.parameterList?.parameters
                    else -> null
                }
                if (params != null && params.isNotEmpty()) {
                    if (idx < params.size) return params[idx].type
                    val last = params.last()
                    if (last.isVarArgs) return (last.type as? PsiEllipsisType)?.componentType
                }
            }
        }
        return null
    }

    private fun isWithin(node: PsiElement, ancestor: PsiElement?): Boolean =
        ancestor != null && PsiTreeUtil.isAncestor(ancestor, node, false)

    private fun boostIfFits(item: CompletionItem, expected: PsiType): CompletionItem {
        val psi = (item.symbol as? JavaSymbol)?.psi ?: return item
        val candidate = when (psi) {
            is PsiMethod -> psi.returnType
            is PsiVariable -> psi.type
            is PsiClass -> PsiTypesUtil.getClassType(psi)
            else -> null
        } ?: return item
        if (candidate.equalsToText("void")) return item
        val fits = runCatching { TypeConversionUtil.isAssignable(expected, candidate) }.getOrDefault(false)
        if (!fits) return item
        return item.copy(relevance = (item.relevance ?: CompletionRelevance.NONE).copy(fitsExpectedType = true))
    }

    /** Names to offer at a variable declaration (`Foo <caret>`): each camel-hump suffix of the type's simple
     *  name, first letter lowered (`HttpClient` → `httpClient`, `client`), plus a single-letter fallback. */
    private fun fillNameSuggestions(variable: PsiVariable, params: CompletionParams, result: CompletionResultSet) {
        val type = variable.type
        val simple = type.presentableText.substringBefore('<').substringAfterLast('.').trimEnd('[', ']', ' ')
        if (simple.isEmpty()) return
        val words = splitCamelHumps(simple)
        val names = LinkedHashSet<String>()
        for (i in words.indices) names += words.subList(i, words.size).joinToString("").replaceFirstChar { it.lowercase() }
        simple.firstOrNull()?.let { names += it.lowercaseChar().toString() }
        names.filter { it.isNotEmpty() && params.prefixMatches(it) }.forEach { n ->
            result.addElement(
                CompletionItem(
                    label = n,
                    insertText = n,
                    kind = CompletionItemKind.VARIABLE,
                    detail = simple,
                    sortPriority = -100, // name suggestions lead at a declaration position
                )
            )
        }
    }

    /** Split a camel-humped identifier into words: `HttpURLClient` → [Http, URL, Client]. */
    private fun splitCamelHumps(name: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in name) {
            if (c.isUpperCase() && cur.isNotEmpty() && !cur.last().isUpperCase()) { out += cur.toString(); cur.clear() }
            cur.append(c)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.ifEmpty { listOf(name) }
    }

    // --- member access ------------------------------------------------------------------------------------

    private fun fillMemberAccess(
        qualifier: PsiExpression,
        place: PsiElement,
        params: CompletionParams,
        result: CompletionResultSet,
        expected: PsiType? = null,
    ) {
        val resolved = (qualifier as? PsiReferenceExpression)?.resolve()
        when (resolved) {
            is PsiPackage -> {
                resolved.subPackages.forEach { emitPackage(it, params, result) }
                resolved.getClasses(scope()).forEach { emitClass(it, params, result) }
            }
            is PsiClass -> emitMembers(resolved, place, staticOnly = true, params, result)
            else -> when (val type = qualifier.type) {
                // `arr.` — arrays have a `length` pseudo-field, a covariant `clone()`, and Object's methods.
                is PsiArrayType -> emitArrayMembers(place, params, result)
                is PsiClassType -> type.resolve()?.let { cls ->
                    emitMembers(cls, place, staticOnly = false, params, result)
                    // One-hop chain completion: when the position expects a type this receiver can't produce
                    // directly, offer `m1().m2()` where a member's result yields the expected type.
                    if (expected != null) addChainCompletions(cls, expected, place, params, result)
                }
                else -> {} // a primitive receiver (shouldn't reach `.`) has no members
            }
        }
        // Postfix templates (`expr.sout` / `expr.nn` / `expr.for` / …) rewrite the whole `receiver.key`.
        addPostfixTemplates(qualifier, resolved, params, result)
        result.stopHere()
    }

    /** One-hop expected-type chains: for a no-arg member `m1()` returning a class type `T1`, if `T1` has a
     *  no-arg member `m2()` whose result is assignable to [expected], offer the chain `m1().m2()` (boosted,
     *  ranked just below a direct fit). Bounded to no-arg calls, one hop, and [CHAIN_LIMIT] results — a cheap
     *  approximation of IntelliJ's chain completion. Only the first fitting `m2` per `m1` is offered. */
    private fun addChainCompletions(cls: PsiClass, expected: PsiType, place: PsiElement, params: CompletionParams, result: CompletionResultSet) {
        if (expected.equalsToText("void")) return
        val helper = env.facade.resolveHelper
        var emitted = 0
        for (m1 in cls.allMethods) {
            if (emitted >= CHAIN_LIMIT) break
            if (m1.isConstructor || m1.parameterList.parametersCount != 0) continue
            if (!params.prefixMatches(m1.name)) continue
            if (!helper.isAccessible(m1, place, null)) continue
            val t1 = (m1.returnType as? PsiClassType)?.resolve() ?: continue
            val m2 = t1.allMethods.firstOrNull { m ->
                !m.isConstructor && m.parameterList.parametersCount == 0 && m.returnType?.let {
                    !it.equalsToText("void") && runCatching { TypeConversionUtil.isAssignable(expected, it) }.getOrDefault(false)
                } == true && helper.isAccessible(m, place, null)
            } ?: continue
            result.addElement(chainItem(m1, m2))
            emitted++
        }
    }

    private fun chainItem(m1: PsiMethod, m2: PsiMethod): CompletionItem {
        val insert = "${m1.name}().${m2.name}()"
        return CompletionItem(
            label = insert,
            insertText = insert,
            kind = CompletionItemKind.METHOD,
            detail = m2.returnType?.presentableText ?: "?",
            container = m1.containingClass?.name,
            symbol = JavaSymbol(m1),
            sortPriority = 10, // below a direct member (0), above keywords
            relevance = CompletionRelevance(fitsExpectedType = true),
        )
    }

    /** Contribute the postfix-template items for `receiver.key` at this member-access position. */
    private fun addPostfixTemplates(
        qualifier: PsiExpression,
        resolved: PsiElement?,
        params: CompletionParams,
        result: CompletionResultSet,
    ) {
        val psi = qualifier.containingFile as? PsiJavaFile
        JavaPostfixTemplates.itemsFor(
            text = params.document.text.toString(),
            keyStart = params.replacementRange.start,
            prefix = params.prefix,
            qualifierType = qualifier.type,
            staticQualifier = resolved is PsiClass, // `Type.new`
            plannedImport = { fqn ->
                psi?.let { JavaImportEdits.planImport(it, fqn) }?.let { TextEdit(TextRange(it.offset, it.offset), it.text) }
            },
        ).forEach { result.addElement(it) }
    }

    /** Members of an array expression: the `length` field, `clone()`, and inherited `java.lang.Object` methods. */
    private fun emitArrayMembers(place: PsiElement, params: CompletionParams, result: CompletionResultSet) {
        if (params.prefixMatches("length")) {
            result.addElement(CompletionItem(label = "length", insertText = "length", kind = CompletionItemKind.FIELD, detail = "int"))
        }
        if (params.prefixMatches("clone")) {
            result.addElement(
                CompletionItem(label = "clone", insertText = "clone()", kind = CompletionItemKind.METHOD, detail = "(): Object", caret = CaretAction.AtEnd),
            )
        }
        val obj = env.facade.findClass("java.lang.Object", scope()) ?: return
        val helper = env.facade.resolveHelper
        obj.methods.forEach { m ->
            if (m.isConstructor || m.name == "clone") return@forEach
            if (params.prefixMatches(m.name) && helper.isAccessible(m, place, null)) result.addElement(methodItem(m))
        }
    }

    private fun emitMembers(
        cls: PsiClass,
        place: PsiElement,
        staticOnly: Boolean,
        params: CompletionParams,
        result: CompletionResultSet,
    ) {
        val helper = env.facade.resolveHelper
        fun accessible(m: PsiMember) = helper.isAccessible(m, place, null)
        cls.allMethods.forEach { m ->
            if (m.isConstructor) return@forEach
            if (staticOnly && !m.hasModifierProperty(PsiModifier.STATIC)) return@forEach
            if (!params.prefixMatches(m.name)) return@forEach
            if (accessible(m)) result.addElement(methodItem(m, callableWeight = callableWeightOf(m, cls)))
        }
        cls.allFields.forEach { f ->
            if (staticOnly && !f.hasModifierProperty(PsiModifier.STATIC)) return@forEach
            if (!params.prefixMatches(f.name)) return@forEach
            if (accessible(f)) result.addElement(fieldItem(f, callableWeight = callableWeightOf(f, cls)))
        }
        cls.allInnerClasses.forEach { c ->
            val n = c.name ?: return@forEach
            if (staticOnly && !c.hasModifierProperty(PsiModifier.STATIC)) return@forEach
            if (params.prefixMatches(n) && accessible(c)) emitClass(c, params, result)
        }
    }

    // --- name / type references ---------------------------------------------------------------------------

    private fun fillNameReference(
        leaf: PsiElement,
        markerOffset: Int,
        psi: PsiJavaFile,
        params: CompletionParams,
        result: CompletionResultSet,
    ) {
        // Lexical scope (locals, params, enclosing-type members), built on the spliced live tree.
        JavaScope(leaf, markerOffset, null, env.facade, env.project)
            .symbols()
            .filter { params.prefixMatches(it.name) }
            .forEach { result.addElement(symbolItem(it)) }
        // Types visible without a qualifier.
        fillTypeReference(psi, params, result, leaf = leaf)
        // Statement-position live templates (`sout`, `fori`, `psvm`, `try`, …).
        JavaLiveTemplates.itemsFor(params.prefix).forEach { result.addElement(it) }
    }

    private fun fillTypeReference(
        psi: PsiJavaFile,
        params: CompletionParams,
        result: CompletionResultSet,
        ctx: TypeCtx = TypeCtx.ANY,
        leaf: PsiElement? = null,
    ) {
        // The index-backed (unimported) candidates are prefix-DEPENDENT and truncated (top-N per query), so the
        // set for `Li` isn't a superset of the set for `Lis` — the editor must re-query as the prefix grows,
        // not narrow a stale list client-side. Marking the result incomplete drives that re-query. (Without it,
        // fast-typing `Lis` kept the truncated `Li` list, so unimported types like `List` never surfaced until
        // a cursor move forced a fresh query.)
        result.markIncomplete()
        val offered = HashSet<String>() // FQNs already offered, so index-backed types don't duplicate them
        visibleTypes(psi, includeBulk = params.prefix.isNotEmpty())
            .filter { it.name != null && params.prefixMatches(it.name!!) && ctx.accepts(it) }
            .forEach { c -> c.qualifiedName?.let { offered += it }; emitClass(c, params, result) }
        emitIndexedTypes(psi, params, offered, result, ctx)
        if (ctx == TypeCtx.ANY) emitKeywords(leaf, params, result) // primitives/keywords are illegal in a type-bound position
    }

    /** Unimported types from the workspace index (auto-import): offer each matching type by simple name, with
     *  an `import` edit unless it needs none (java.lang / same package / already offered in scope). */
    private fun emitIndexedTypes(
        psi: PsiJavaFile,
        params: CompletionParams,
        offered: MutableSet<String>,
        result: CompletionResultSet,
        ctx: TypeCtx = TypeCtx.ANY,
    ) {
        if (params.prefix.length < 2) return // avoid dumping the world on a 1-char prefix
        val pkg = psi.packageName
        typeSearch(params.prefix).forEach { t ->
            if (!ctx.acceptsKind(t.kind)) return@forEach
            if (!offered.add(t.fqn)) return@forEach
            val simple = t.fqn.substringAfterLast('.')
            if (!params.prefixMatches(simple)) return@forEach
            val typePkg = t.fqn.substringBeforeLast('.', "")
            val needsImport = typePkg.isNotEmpty() && typePkg != "java.lang" && typePkg != pkg
            val edits = if (needsImport) importEdit(psi, t.fqn)?.let { listOf(it) } ?: emptyList() else emptyList()
            result.addElement(
                CompletionItem(
                    label = simple,
                    insertText = simple,
                    kind = classKindFromString(t.kind),
                    container = typePkg.ifEmpty { null },
                    additionalEdits = edits,
                    relevance = CompletionRelevance(inScope = !needsImport, proximity = PROX_INDEX),
                )
            )
        }
    }

    /** Callable grouping for a member accessed via `.`: a member declared on the receiver class is closest (0);
     *  an inherited member sits in the middle (2); `java.lang.Object`'s methods sink to the bottom (4). */
    private fun callableWeightOf(member: PsiMember, receiver: PsiClass): Int {
        val owner = member.containingClass ?: return 0
        return when {
            owner === receiver || owner.qualifiedName == receiver.qualifiedName -> 0
            owner.qualifiedName == "java.lang.Object" -> 4
            else -> 2
        }
    }

    /** A `import <fqn>;` edit spliced in sorted position, or null if already imported (or on-demand covered). */
    private fun importEdit(psi: PsiJavaFile, fqn: String): TextEdit? {
        val plan = JavaImportEdits.planImport(psi, fqn) ?: return null
        return TextEdit(TextRange(plan.offset, plan.offset), plan.text)
    }

    private fun classKindFromString(kind: String): CompletionItemKind = when (kind) {
        "interface" -> CompletionItemKind.INTERFACE
        "enum" -> CompletionItemKind.ENUM
        "annotation" -> CompletionItemKind.ANNOTATION_TYPE
        "record" -> CompletionItemKind.RECORD
        else -> CompletionItemKind.CLASS
    }

    /** Scope-gated Java keywords, prefix-filtered. Offered in name/type positions (not after `expr.` / `pkg.`),
     *  ranked below real symbols via `sortPriority`. Only the keywords legal at [leaf] are offered (see
     *  [JavaKeywords]); a null leaf (a pure type-reference position with no marker leaf) offers none. */
    private fun emitKeywords(leaf: PsiElement?, params: CompletionParams, result: CompletionResultSet) {
        if (leaf == null) return
        JavaKeywords.itemsFor(leaf) { params.prefixMatches(it) }.forEach { result.addElement(it) }
    }

    private fun fillQualifiedTypeReference(
        qualifier: PsiJavaCodeReferenceElement,
        place: PsiElement,
        params: CompletionParams,
        result: CompletionResultSet,
        ctx: TypeCtx = TypeCtx.ANY,
    ) {
        when (val target = qualifier.resolve()) {
            is PsiPackage -> {
                target.subPackages.forEach { emitPackage(it, params, result) }
                target.getClasses(scope()).forEach { if (ctx.accepts(it)) emitClass(it, params, result) }
            }
            is PsiClass -> {
                target.allInnerClasses.forEach { c ->
                    if (c.name != null && params.prefixMatches(c.name!!) && ctx.accepts(c)) emitClass(c, params, result)
                }
            }
        }
        result.stopHere()
    }

    /** Types resolvable in [psi] by simple name: its own classes, imports, same-package, and java.lang. */
    private fun visibleTypes(psi: PsiJavaFile, includeBulk: Boolean): List<PsiClass> {
        val out = LinkedHashSet<PsiClass>()
        psi.classes.forEach { out += it }
        psi.importList?.let { il ->
            il.importStatements.forEach { imp ->
                when (val t = imp.resolve()) {
                    is PsiClass -> out += t
                    is PsiPackage -> if (imp.isOnDemand) out += t.getClasses(scope())
                    else -> {}
                }
            }
        }
        if (includeBulk) {
            env.facade.findPackage(psi.packageName)?.getClasses(scope())?.forEach { out += it }
            env.facade.findPackage("java.lang")?.getClasses(scope())?.forEach { out += it }
        }
        return out.toList()
    }

    // --- type-reference context (extends / implements / new / throws / catch) -----------------------------

    /** The kind of type legal at a type-reference position, so completion offers only usable candidates. */
    private enum class TypeCtx {
        ANY,
        CLASS_EXTENDS,  // a class's `extends` — a non-final class
        INTERFACE,      // `implements`, or an interface's `extends` — an interface
        THROWABLE,      // a `throws` clause or `catch (…)` — a Throwable subtype
        INSTANTIABLE,   // `new X(…)` — a concrete, instantiable type
        ANNOTATION;     // `@X` — an annotation type

        fun accepts(c: PsiClass): Boolean = when (this) {
            ANY -> true
            CLASS_EXTENDS -> !c.isInterface && !c.isAnnotationType && !c.isEnum && !c.hasModifierProperty(PsiModifier.FINAL)
            INTERFACE -> c.isInterface && !c.isAnnotationType
            THROWABLE -> InheritanceUtil.isInheritor(c, "java.lang.Throwable")
            INSTANTIABLE -> !c.isInterface && !c.isAnnotationType && !c.isEnum && !c.hasModifierProperty(PsiModifier.ABSTRACT)
            ANNOTATION -> c.isAnnotationType
        }

        /** Coarse filter for index-backed (unresolved) candidates, which carry only a kind string. Precise for
         *  extends/implements (interface vs class); permissive for the type-membership contexts it can't verify. */
        fun acceptsKind(kind: String): Boolean = when (this) {
            ANY -> true
            CLASS_EXTENDS -> kind == "class"                    // records/enums/annotations/interfaces excluded
            INTERFACE -> kind == "interface"
            THROWABLE -> kind == "class"                        // can't check Throwable-ness without resolving
            INSTANTIABLE -> kind == "class" || kind == "record" // can't check abstract without resolving
            ANNOTATION -> kind == "annotation"
        }
    }

    private fun typeContext(ref: PsiJavaCodeReferenceElement): TypeCtx {
        // `@X` — the ref is an annotation's name element; offer only annotation types.
        PsiTreeUtil.getParentOfType(ref, PsiAnnotation::class.java, false)?.let { ann ->
            if (ann.nameReferenceElement === ref) return TypeCtx.ANNOTATION
        }
        PsiTreeUtil.getParentOfType(ref, PsiNewExpression::class.java, false)?.let { newExpr ->
            if (newExpr.classReference === ref) return TypeCtx.INSTANTIABLE
        }
        PsiTreeUtil.getParentOfType(ref, PsiReferenceList::class.java, false)?.let { list ->
            return when (list.role) {
                PsiReferenceList.Role.EXTENDS_LIST ->
                    if ((list.parent as? PsiClass)?.isInterface == true) TypeCtx.INTERFACE else TypeCtx.CLASS_EXTENDS
                PsiReferenceList.Role.IMPLEMENTS_LIST -> TypeCtx.INTERFACE
                PsiReferenceList.Role.THROWS_LIST -> TypeCtx.THROWABLE
                else -> TypeCtx.ANY // EXTENDS_BOUNDS_LIST (type-param bound) may be a class OR interface
            }
        }
        // `catch (X e)` — the ref is inside the catch parameter's type (not the catch body).
        PsiTreeUtil.getParentOfType(ref, PsiParameter::class.java, false)?.let { p ->
            if (p.declarationScope is PsiCatchSection && p.typeElement?.let { PsiTreeUtil.isAncestor(it, ref, false) } == true) {
                return TypeCtx.THROWABLE
            }
        }
        return TypeCtx.ANY
    }

    // --- subtype completion at `new` (`Foo x = new <caret>` → Foo's concrete impls) -----------------------

    private fun addSubtypeCompletions(expected: PsiType, psi: PsiJavaFile, params: CompletionParams, result: CompletionResultSet) {
        val superFqn = (expected as? PsiClassType)?.resolve()?.qualifiedName ?: return
        val pkg = psi.packageName
        for (t in subtypeSearch(superFqn)) {
            if (t.kind == "interface" || t.kind == "annotation" || t.kind == "enum") continue // not instantiable
            val simple = t.fqn.substringAfterLast('.')
            if (!params.prefixMatches(simple)) continue
            val typePkg = t.fqn.substringBeforeLast('.', "")
            val needsImport = typePkg.isNotEmpty() && typePkg != "java.lang" && typePkg != pkg
            val edits = if (needsImport) importEdit(psi, t.fqn)?.let { listOf(it) } ?: emptyList() else emptyList()
            result.addElement(
                CompletionItem(
                    label = simple,
                    insertText = simple,
                    kind = classKindFromString(t.kind),
                    container = typePkg.ifEmpty { null },
                    additionalEdits = edits,
                    // A subtype offered at `new` fits the expected type by construction → float it up.
                    relevance = CompletionRelevance(fitsExpectedType = true, inScope = !needsImport),
                )
            )
        }
    }

    // --- case-label completion (`case <caret>` on an enum → the selector's constants) --------------------

    /** When [leaf] sits in a `case` label whose switch selector is an enum, offer that enum's constants by
     *  simple name, boosted (they fit by construction). No-op otherwise. */
    private fun addSwitchCaseCompletions(leaf: PsiElement, params: CompletionParams, result: CompletionResultSet) {
        PsiTreeUtil.getParentOfType(leaf, PsiSwitchLabelStatementBase::class.java, false) ?: return
        val switch = PsiTreeUtil.getParentOfType(leaf, PsiSwitchBlock::class.java, false) ?: return
        val enumClass = (switch.expression?.type as? PsiClassType)?.resolve()?.takeIf { it.isEnum } ?: return
        enumClass.fields.forEach { f ->
            if (f is com.intellij.psi.PsiEnumConstant && params.prefixMatches(f.name)) {
                result.addElement(
                    CompletionItem(
                        label = f.name,
                        insertText = f.name,
                        kind = CompletionItemKind.ENUM_CONSTANT,
                        detail = enumClass.name,
                        symbol = JavaSymbol(f),
                        relevance = CompletionRelevance(fitsExpectedType = true, proximity = PROX_MEMBER),
                    ),
                )
            }
        }
    }

    // --- override completion (`toStr` at class level → an @Override stub) ---------------------------------

    /** Whether [leaf] sits at a class-body MEMBER declaration position — directly in a class, not inside a
     *  method/initializer body, a field-initializer/annotation expression, a parameter list, or an
     *  extends/implements/throws list. */
    private fun isMemberPosition(leaf: PsiElement): Boolean {
        val cls = PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false) ?: return false
        PsiTreeUtil.getParentOfType(leaf, PsiCodeBlock::class.java, false)?.let { if (PsiTreeUtil.isAncestor(cls, it, false)) return false }
        PsiTreeUtil.getParentOfType(leaf, PsiExpression::class.java, false)?.let { if (PsiTreeUtil.isAncestor(cls, it, false)) return false }
        if (PsiTreeUtil.getParentOfType(leaf, PsiParameterList::class.java, false) != null) return false
        if (PsiTreeUtil.getParentOfType(leaf, PsiReferenceList::class.java, false) != null) return false
        return true
    }

    private fun addOverrideCompletions(leaf: PsiElement, insertStart: Int, params: CompletionParams, result: CompletionResultSet) {
        val cls = PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false) ?: return
        if (cls.isAnnotationType) return
        val indent = lineIndent(params.document.text, insertStart)
        for (m in overridableMethods(cls)) {
            if (!params.prefixMatches(m.name)) continue
            result.addElement(overrideItem(m, indent))
        }
    }

    /** Superclass methods this class may override: non-final / non-static / non-private instance methods it
     *  hasn't already declared, most-derived per erased signature (so `Object`'s `toString`/`equals` show). */
    private fun overridableMethods(cls: PsiClass): Collection<PsiMethod> {
        val own = cls.methods.mapTo(HashSet()) { dev.ide.lang.java.resolve.JavaOverrides.erasedSignature(it) }
        val out = LinkedHashMap<String, PsiMethod>()
        for (m in cls.allMethods) {
            if (m.isConstructor || m.containingClass === cls) continue
            if (m.hasModifierProperty(PsiModifier.FINAL) || m.hasModifierProperty(PsiModifier.STATIC) ||
                m.hasModifierProperty(PsiModifier.PRIVATE)
            ) continue
            val sig = dev.ide.lang.java.resolve.JavaOverrides.erasedSignature(m)
            if (sig in own) continue
            out.putIfAbsent(sig, m)
        }
        return out.values
    }

    private fun overrideItem(m: PsiMethod, indent: String): CompletionItem {
        val ret = m.returnType?.presentableText ?: "void"
        val params = m.parameterList.parameters.joinToString(", ") { "${it.type.presentableText} ${it.name}" }
        val argNames = m.parameterList.parameters.joinToString(", ") { it.name ?: "arg" }
        val vis = when {
            m.hasModifierProperty(PsiModifier.PUBLIC) -> "public "
            m.hasModifierProperty(PsiModifier.PROTECTED) -> "protected "
            else -> ""
        }
        val body = when {
            m.hasModifierProperty(PsiModifier.ABSTRACT) -> "throw new UnsupportedOperationException();"
            ret == "void" -> "super.${m.name}($argNames);"
            else -> "return super.${m.name}($argNames);"
        }
        val exp = snippet {
            text("@Override\n$indent$vis$ret ${m.name}($params) {\n$indent    $body")
            finalHere()
            text("\n$indent}")
        }
        return CompletionItem(
            label = m.name,
            insertText = exp.text,
            kind = CompletionItemKind.METHOD,
            detail = "override in ${m.containingClass?.name ?: "?"}",
            sortPriority = -30, // an override at a member position is a strong intent
            caret = CaretAction.ExpandSnippet(exp),
        )
    }

    /** The leading whitespace of the line containing [offset] (for aligning a multi-line override stub). */
    private fun lineIndent(text: CharSequence, offset: Int): String {
        var ls = offset.coerceIn(0, text.length)
        while (ls > 0 && text[ls - 1] != '\n') ls--
        val sb = StringBuilder()
        var i = ls
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) { sb.append(text[i]); i++ }
        return sb.toString()
    }

    // --- item construction --------------------------------------------------------------------------------

    private fun methodItem(m: PsiMethod, proximity: Int = 0, callableWeight: Int = 0): CompletionItem {
        val hasParams = m.parameterList.parametersCount > 0
        val insert = "${m.name}()"
        val paramText = m.parameterList.parameters.joinToString(", ") { "${it.type.presentableText} ${it.name}" }
        return CompletionItem(
            label = m.name,
            insertText = insert,
            kind = CompletionItemKind.METHOD,
            detail = "($paramText): ${m.returnType?.presentableText ?: "void"}",
            container = m.containingClass?.name,
            symbol = JavaSymbol(m),
            caret = if (hasParams) CaretAction.At(m.name.length + 1) else CaretAction.AtEnd,
            relevance = CompletionRelevance(deprecated = m.isDeprecated, proximity = proximity, callableWeight = callableWeight),
        )
    }

    private fun fieldItem(f: PsiField, proximity: Int = 0, callableWeight: Int = 0): CompletionItem = CompletionItem(
        label = f.name,
        insertText = f.name,
        kind = if (f is com.intellij.psi.PsiEnumConstant) CompletionItemKind.ENUM_CONSTANT else CompletionItemKind.FIELD,
        detail = f.type.presentableText,
        container = f.containingClass?.name,
        symbol = JavaSymbol(f),
        relevance = CompletionRelevance(deprecated = f.isDeprecated, proximity = proximity, callableWeight = callableWeight),
    )

    private fun emitClass(c: PsiClass, params: CompletionParams, result: CompletionResultSet, proximity: Int = PROX_TYPE) {
        val n = c.name ?: return
        result.addElement(
            CompletionItem(
                label = n,
                insertText = n,
                kind = classItemKind(c),
                container = (c.containingFile as? PsiJavaFile)?.packageName?.ifEmpty { null }
                    ?: c.qualifiedName?.substringBeforeLast('.', ""),
                symbol = JavaSymbol(c),
                relevance = CompletionRelevance(deprecated = c.isDeprecated, proximity = proximity),
            )
        )
    }

    private fun emitPackage(p: PsiPackage, params: CompletionParams, result: CompletionResultSet) {
        val n = p.name ?: return
        if (!params.prefixMatches(n)) return
        result.addElement(CompletionItem(label = n, insertText = n, kind = CompletionItemKind.PACKAGE))
    }

    /** A scope symbol at a bare-name position. Proximity ranks locals/params over enclosing-type members over
     *  types, so a nearby variable outranks a same-prefix library class (the KindWeigher signal Java lacked). */
    private fun symbolItem(s: Symbol): CompletionItem {
        val prox = when (s.kind) {
            SymbolKind.LOCAL_VARIABLE, SymbolKind.PARAMETER -> PROX_LOCAL
            SymbolKind.FIELD, SymbolKind.METHOD, SymbolKind.ENUM_CONSTANT -> PROX_MEMBER
            else -> PROX_TYPE
        }
        val psi = (s as? JavaSymbol)?.psi
        if (psi is PsiMethod) return methodItem(psi, proximity = prox)
        if (psi is PsiField) return fieldItem(psi, proximity = prox)
        return CompletionItem(
            label = s.name,
            insertText = s.name,
            kind = symbolItemKind(s.kind),
            detail = s.type?.qualifiedName,
            symbol = s,
            relevance = CompletionRelevance(proximity = prox),
        )
    }

    private fun classItemKind(c: PsiClass): CompletionItemKind = when {
        c.isAnnotationType -> CompletionItemKind.ANNOTATION_TYPE
        c.isEnum -> CompletionItemKind.ENUM
        c.isInterface -> CompletionItemKind.INTERFACE
        c.isRecord -> CompletionItemKind.RECORD
        else -> CompletionItemKind.CLASS
    }

    private fun symbolItemKind(k: SymbolKind): CompletionItemKind = when (k) {
        SymbolKind.CLASS -> CompletionItemKind.CLASS
        SymbolKind.INTERFACE -> CompletionItemKind.INTERFACE
        SymbolKind.ENUM -> CompletionItemKind.ENUM
        SymbolKind.ANNOTATION_TYPE -> CompletionItemKind.ANNOTATION_TYPE
        SymbolKind.RECORD -> CompletionItemKind.RECORD
        SymbolKind.METHOD -> CompletionItemKind.METHOD
        SymbolKind.CONSTRUCTOR -> CompletionItemKind.CONSTRUCTOR
        SymbolKind.FIELD -> CompletionItemKind.FIELD
        SymbolKind.ENUM_CONSTANT -> CompletionItemKind.ENUM_CONSTANT
        SymbolKind.LOCAL_VARIABLE -> CompletionItemKind.VARIABLE
        SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
        SymbolKind.TYPE_PARAMETER -> CompletionItemKind.TYPE_PARAMETER
        SymbolKind.PACKAGE -> CompletionItemKind.PACKAGE
    }

    private fun scope() = GlobalSearchScope.allScope(env.project)

    companion object {
        /** IntelliJ's canonical completion marker — a valid identifier the parser treats as a real name. */
        private const val DUMMY = "IntellijIdeaRulezzz"
        // Keyword completion is scope-gated in JavaKeywords (the flat reserved-word list was position-blind).

        // Declaration-proximity tiers (lower = nearer) read by the engine's KindWeigher. Java left this at 0
        // (no signal) so a local never outranked a same-prefix library type; these give it Kotlin's ordering.
        private const val PROX_LOCAL = 1   // a local variable / parameter
        private const val PROX_MEMBER = 2  // an enclosing-type member reachable by bare name
        private const val PROX_TYPE = 3    // a type visible without an import (own / imported / same-package)
        private const val PROX_INDEX = 4   // an unimported type from the workspace index

        /** Max one-hop chain (`m1().m2()`) suggestions offered at a member-access position. */
        private const val CHAIN_LIMIT = 8
    }
}
