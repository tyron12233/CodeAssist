package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.symbols.KotlinType

/*
 * Clashes a class inherits from two supertypes at once, ported from K2:
 *
 *  - RETURN_TYPE_MISMATCH_ON_INHERITANCE / PROPERTY_TYPE_MISMATCH_ON_INHERITANCE / VAR_TYPE_MISMATCH_ON_INHERITANCE
 *    (and their BY_DELEGATION forms), from `FirImplementationMismatchChecker.checkInheritanceClash`: a member the
 *    class does not override reaches it from several supertypes whose types no single one of them can stand for
 *    (none is a subtype of all the others, or equal to a `var`'s), or whose one implementation does not fit the
 *    others' types.
 *  - INCONSISTENT_TYPE_PARAMETER_VALUES, from `checkInconsistentTypeParameters` (FirInconsistentTypeParameterHelpers),
 *    which `FirSupertypesChecker` runs on a class with more than one supertype: one generic class reached through
 *    the supertypes with two different type arguments for the same parameter.
 *
 * Judged over project source in the class's own file, with every type read off the declarations and the
 * supertypes' type arguments substituted down the hierarchy. A hierarchy holding anything else (a library
 * supertype, a typealias, an inferred member type, a generic member function) is left alone for that member.
 */

internal fun KotlinSemanticChecks.inheritanceClashCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtClassOrObject::class) { psi ->
        psi as KtClassOrObject
        KotlinPerf.span("sem.ported.inheritanceClash") {
            if (resolveReady) {
                reportAll(InheritanceClashes(this@inheritanceClashCheckers, resolver, psi).typeMismatches())
                reportAll(InheritanceClashes(this@inheritanceClashCheckers, resolver, psi).inconsistentTypeParameterValues())
            }
        }
    },
)

/** The K2 names each code of this file stands for, merged into [KotlinSuppression.K2_NAMES]. */
internal val INHERITANCE_CLASH_K2_NAMES: Map<String, Set<String>> = mapOf(
    KotlinDiagnosticCodes.INHERITANCE_TYPE_MISMATCH to setOf(
        "RETURN_TYPE_MISMATCH_ON_INHERITANCE", "PROPERTY_TYPE_MISMATCH_ON_INHERITANCE", "VAR_TYPE_MISMATCH_ON_INHERITANCE",
        "RETURN_TYPE_MISMATCH_BY_DELEGATION", "PROPERTY_TYPE_MISMATCH_BY_DELEGATION",
    ),
    KotlinDiagnosticCodes.INCONSISTENT_TYPE_PARAMETER_VALUES to setOf("INCONSISTENT_TYPE_PARAMETER_VALUES"),
)

private class InheritanceClashes(val checks: KotlinSemanticChecks, val resolver: KotlinResolver, val cls: KtClassOrObject) {
    private val service = checks.service
    private val typing = KotlinStrictSubtyping(service) { if (unboundedOwnTypeParameter(it)) UNBOUNDED_TYPE_PARAMETER else null }

    /** A class reached in the hierarchy, its type parameters bound to what the path to it supplies. */
    private class Reached(val psi: KtClassOrObject, val subst: Map<String, KotlinType>, val type: KotlinType)

    /** One inherited member as some supertype declares it, its type substituted. Null [type]: not readable. */
    private class Member(
        val decl: KtElement, val owner: KtClassOrObject, val type: KotlinType?, val isProperty: Boolean,
        val isVar: Boolean, val abstract: Boolean, var delegated: Boolean = false, var via: Int = -1,
    )

    private fun applies(): Boolean {
        if (cls is KtEnumEntry) return false
        if (cls is KtClass && (cls.isEnum() || cls.isAnnotation())) return false
        if (cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return false
        return cls.superTypeListEntries.size >= 2
    }

    // --- RETURN / PROPERTY / VAR_TYPE_MISMATCH_ON_INHERITANCE --------------------------------------------------

    fun typeMismatches(): List<Diagnostic> {
        if (!applies()) return emptyList()
        val ownNames = HashSet<String>()
        cls.declarations.forEach { d -> if (d is KtNamedFunction || d is KtProperty) (d as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name?.let { ownNames += it } }
        cls.primaryConstructorParameters.filter { it.hasValOrVar() }.mapNotNullTo(ownNames) { it.name }
        val byKey = LinkedHashMap<String, MutableList<Member>>()
        cls.superTypeListEntries.forEachIndexed { i, entry ->
            val reached = reach(entry, ownSubst()) ?: return@forEachIndexed
            if (reached.psi.containingFile !== cls.containingFile) return emptyList()
            val effective = effective(reached, HashSet()) ?: return emptyList()
            for ((key, ms) in effective) for (m in ms) {
                m.via = i
                if (entry is KtDelegatedSuperTypeEntry) m.delegated = true
                byKey.getOrPut(key) { ArrayList() } += m
            }
        }
        val out = ArrayList<Diagnostic>()
        val reported = HashSet<String>()
        for ((key, all) in byKey) {
            if (key.substringAfter(':').substringBefore('(') in ownNames) continue
            val members = all.distinctBy { it.decl }
            if (members.map { it.via }.distinct().size < 2) continue
            // A supertype that brings two of them is an intersection of its own, judged on that supertype.
            if (members.groupBy { it.via }.values.any { it.size > 1 }) continue
            // A member another one overrides is subsumed by it.
            val kept = members.filter { m -> members.none { o -> o !== m && o.owner !== m.owner && isSubclass(o.owner, m.owner) } }
            if (kept.size < 2 || kept.any { it.type == null }) continue
            val code = clash(kept) ?: continue
            if (reported.add(code)) out += report(code)
        }
        return out
    }

    /** The K2 name of the clash among [ms], or null when they are compatible or the types cannot be judged. */
    private fun clash(ms: List<Member>): String? {
        fun canOverride(a: Member, b: Member): Boolean? =
            if (b.isProperty && b.isVar) typing.equalTypes(a.type!!, b.type!!) else typing.subtype(a.type!!, b.type!!)
        val verdicts = HashMap<Pair<Member, Member>, Boolean>()
        for (a in ms) for (b in ms) verdicts[a to b] = canOverride(a, b) ?: return null
        val compatible = ms.any { a -> ms.all { b -> verdicts.getValue(a to b) } }
        if (!compatible) {
            for (a in ms) for (b in ms) {
                if (!verdicts.getValue(a to b) && !verdicts.getValue(b to a)) return kind(a, b, delegation = false)
            }
        }
        val delegation = ms.firstOrNull { it.delegated }
        val impl = delegation ?: ms.filter { !it.abstract }.singleOrNull() ?: return null
        val conflict = ms.firstOrNull { !verdicts.getValue(impl to it) } ?: return null
        return kind(impl, conflict, delegation != null)
    }

    private fun kind(a: Member, b: Member, delegation: Boolean): String = when {
        a.isProperty && b.isProperty -> when {
            a.isVar || b.isVar -> "VAR_TYPE_MISMATCH_ON_INHERITANCE"
            delegation -> "PROPERTY_TYPE_MISMATCH_BY_DELEGATION"
            else -> "PROPERTY_TYPE_MISMATCH_ON_INHERITANCE"
        }
        delegation -> "RETURN_TYPE_MISMATCH_BY_DELEGATION"
        else -> "RETURN_TYPE_MISMATCH_ON_INHERITANCE"
    }

    private fun report(k2: String): Diagnostic {
        val r = (cls.nameIdentifier ?: (cls as? KtObjectDeclaration)?.objectKeyword ?: cls).textRange
        val what = when (k2) {
            "VAR_TYPE_MISMATCH_ON_INHERITANCE" -> "property types"
            "PROPERTY_TYPE_MISMATCH_ON_INHERITANCE", "PROPERTY_TYPE_MISMATCH_BY_DELEGATION" -> "property types"
            else -> "return types"
        }
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'${cls.name ?: "<anonymous>"}' inherits members whose $what do not match", KotlinDiagnosticCodes.INHERITANCE_TYPE_MISMATCH,
        )
    }

    /** The members [r] has, declared or inherited, by key, the most derived declaration first; null when a part of
     *  the hierarchy cannot be read. */
    private fun effective(r: Reached, path: MutableSet<KtClassOrObject>): Map<String, List<Member>>? {
        if (!path.add(r.psi)) return emptyMap()
        val own = declared(r) ?: return null
        val out = LinkedHashMap<String, MutableList<Member>>()
        own.forEach { (k, m) -> out[k] = mutableListOf(m) }
        for (entry in r.psi.superTypeListEntries) {
            val s = reach(entry, r.subst) ?: continue
            if (s.psi.containingFile !== cls.containingFile) return null
            val inherited = effective(s, path) ?: return null
            for ((k, ms) in inherited) if (k !in own) out.getOrPut(k) { ArrayList() } += ms
        }
        path.remove(r.psi)
        return out
    }

    /** The members [r]'s class declares itself, by key (`f:name(param types)` or `p:name`). */
    private fun declared(r: Reached): Map<String, Member>? {
        val out = LinkedHashMap<String, Member>()
        val c = r.psi
        val isInterface = c is KtClass && c.isInterface()
        for (d in c.declarations) {
            if (d.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
            when (d) {
                is KtNamedFunction -> {
                    val name = d.name ?: continue
                    if (d.typeParameters.isNotEmpty() || d.receiverTypeReference != null) continue
                    if (d.modifierList?.text?.contains("context") == true) continue
                    val params = d.valueParameters.map { p -> typeOf(p.typeReference, r) ?: return null }
                    val ret = when {
                        d.typeReference != null -> typeOf(d.typeReference, r)
                        d.hasBlockBody() -> service.typeByFqn("kotlin.Unit")
                        else -> null
                    }
                    val abstract = d.hasModifier(KtTokens.ABSTRACT_KEYWORD) || (isInterface && !d.hasBody())
                    out["f:$name(${params.joinToString(",") { render(it) }})"] = Member(d, c, ret, false, false, abstract)
                }
                is KtProperty -> {
                    val name = d.name ?: continue
                    if (d.receiverTypeReference != null) continue
                    val abstract = d.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                        (isInterface && d.initializer == null && d.accessors.none { it.hasBody() })
                    out["p:$name"] = Member(d, c, d.typeReference?.let { typeOf(it, r) }, true, d.isVar, abstract)
                }
            }
        }
        for (p in c.primaryConstructorParameters) {
            if (!p.hasValOrVar() || p.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
            val name = p.name ?: continue
            out["p:$name"] = Member(p, c, typeOf(p.typeReference, r), true, p.isMutable, false)
        }
        return out
    }

    // --- INCONSISTENT_TYPE_PARAMETER_VALUES -------------------------------------------------------------------

    fun inconsistentTypeParameterValues(): List<Diagnostic> {
        if (!applies()) return emptyList()
        // Per generic class reached, per type parameter: the distinct arguments the hierarchy gives it.
        val values = LinkedHashMap<Pair<KtClassOrObject, String>, MutableList<KotlinType>>()
        val visited = HashSet<String>()
        fun walk(entries: List<KtSuperTypeListEntry>, subst: Map<String, KotlinType>): Boolean {
            for (entry in entries) {
                val s = reach(entry, subst) ?: continue
                if (s.psi.containingFile !== cls.containingFile) continue
                for (tp in s.psi.typeParameters) {
                    val name = tp.name ?: return false
                    val v = s.subst[name] ?: return false
                    val seen = values.getOrPut(s.psi to name) { ArrayList() }
                    if (seen.none { sameArgument(it, v) }) seen += v
                }
                if (!visited.add(render(s.type))) continue
                if (!walk(s.psi.superTypeListEntries, s.subst)) return false
            }
            return true
        }
        if (!walk(cls.superTypeListEntries, ownSubst())) return emptyList()
        val list = cls.superTypeList ?: return emptyList()
        val r = list.textRange
        return values.filter { it.value.size > 1 }.map { (key, vs) ->
            Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Type parameter ${key.second} of '${key.first.name}' has inconsistent values: ${vs.joinToString { render(it) }}",
                KotlinDiagnosticCodes.INCONSISTENT_TYPE_PARAMETER_VALUES,
            )
        }
    }

    /** Two arguments for one parameter are the same when their types are (a projection's kind aside), a star
     *  projection only being the same as itself. */
    private fun sameArgument(a: KotlinType, b: KotlinType): Boolean {
        if (a.projection == "*" || b.projection == "*") return a === b
        return render(a.withProjection("")) == render(b.withProjection(""))
    }

    // --- types ---------------------------------------------------------------------------------------------------

    /** The checked class's own type parameters, as themselves. */
    private fun ownSubst(): Map<String, KotlinType> =
        cls.typeParameters.mapNotNull { it.name }.associateWith { KotlinType(it, isTypeParameter = true) }

    /** The source class [entry] names, its type parameters bound to the entry's arguments under [subst]. Null for
     *  a supertype outside project source, a typealias, or an argument list that does not fit. */
    private fun reach(entry: KtSuperTypeListEntry, subst: Map<String, KotlinType>): Reached? {
        val typeRef = entry.typeReference ?: return null
        var element = typeRef.typeElement
        if (element is KtNullableType) return null
        val user = element as? KtUserType ?: return null
        val fqn = checks.classifierFqnOf(typeRef, resolver) ?: return null
        val psi = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
        // A typealias names its class under another name, with its own parameter order.
        if (user.referencedName != psi.name) return null
        val params = psi.typeParameters.mapNotNull { it.name }
        if (params.size != psi.typeParameters.size) return null
        val args = user.typeArguments
        if (args.size != params.size) return null
        val bound = LinkedHashMap<String, KotlinType>()
        for ((i, a) in args.withIndex()) {
            val t = when (a.projectionKind) {
                KtProjectionKind.STAR -> KotlinType("*", projection = "*")
                else -> {
                    val inner = typeIn(a.typeReference, entry, subst) ?: return null
                    when (a.projectionKind) {
                        KtProjectionKind.IN -> inner.withProjection("in")
                        KtProjectionKind.OUT -> inner.withProjection("out")
                        else -> inner
                    }
                }
            }
            bound[params[i]] = t
        }
        return Reached(psi, bound, KotlinType(fqn, args.indices.map { bound.getValue(params[it]) }))
    }

    /** A member type written in [r]'s class, its type parameters replaced by what reaches them. */
    private fun typeOf(ref: KtTypeReference?, r: Reached): KotlinType? = typeIn(ref, r.psi, r.subst)

    private fun typeIn(ref: KtTypeReference?, at: KtElement, subst: Map<String, KotlinType>): KotlinType? {
        val text = ref?.text ?: return null
        if ('(' in text || '&' in text || '@' in text) return null
        val owner = at as? KtClassOrObject ?: at.parents().firstOrNull { it is KtClassOrObject } as? KtClassOrObject
        val names = owner?.typeParameters?.mapNotNull { it.name }?.toSet().orEmpty()
        val parsed = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(ref)) ?: return null
        val marked = service.markDeclaredTypeParameters(parsed, names) ?: return null
        val t = substituteKeepingNullability(marked, subst) ?: return null
        return if (known(t)) t else null
    }

    private fun KtElement.parents(): Sequence<KtElement> = generateSequence(parent as? KtElement) { it.parent as? KtElement }

    private fun known(t: KotlinType): Boolean {
        if (t.projection == "*") return true
        if (!t.isTypeParameter && !service.isKnownType(canonicalTypeForCheck(t).qualifiedName)) return false
        return t.typeArguments.all { (it as? KotlinType)?.let(::known) == true }
    }

    private fun render(t: KotlinType): String = buildString {
        if (t.projection.isNotEmpty() && t.projection != "*") append(t.projection).append(' ')
        if (t.projection == "*") { append('*'); return@buildString }
        append(canonicalTypeForCheck(t).qualifiedName)
        if (t.typeArguments.isNotEmpty()) {
            append('<')
            append(t.typeArguments.joinToString(",") { (it as? KotlinType)?.let(::render) ?: "?" })
            append('>')
        }
        if (t.nullable) append('?')
    }

    private fun isSubclass(sub: KtClassOrObject, sup: KtClassOrObject): Boolean {
        val seen = HashSet<KtClassOrObject>()
        fun visit(c: KtClassOrObject): Boolean {
            if (!seen.add(c)) return false
            for (e in c.superTypeListEntries) {
                val fqn = e.typeReference?.let { checks.classifierFqnOf(it, resolver) } ?: continue
                val psi = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: continue
                if (psi === sup || visit(psi)) return true
            }
            return false
        }
        return visit(sub)
    }

    private fun unboundedOwnTypeParameter(name: String): Boolean {
        val tp = cls.typeParameters.firstOrNull { it.name == name } ?: return false
        if (tp.extendsBound != null) return false
        return (cls as? KtClass)?.typeConstraints?.none { it.subjectTypeParameterName?.getReferencedName() == name } ?: true
    }
}
