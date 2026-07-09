package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Type-aware Kotlin coloring. One PSI walk over the live parse (the same model
 * [KotlinSourceAnalyzer.semanticDiagnostics] uses), emitting a [SemanticToken] per identifier it can
 * classify CONFIDENTLY off the parse-only symbol model:
 *  - declarations (classes/objects, functions, properties, parameters, type parameters) — exact from PSI;
 *  - call sites resolved to a function — with the Kotlin distinctions the user asked for:
 *    `@Composable` (Android-Studio style), extension, and `suspend`;
 *  - local variables / parameters in use, with `var` (mutable) vs `val` (read-only);
 *  - destructuring bindings (`val (a, b) = …`, `for ((k, v) in …)`, `{ (a, b) -> }`) at declaration and use;
 *  - type references, separating a type parameter (`T`) from a class;
 *  - inside string literals: interpolated variables/expressions (`$name`, `${p.x}` — via the normal walk), plus
 *    the interpolation delimiters (`$`/`${`/`}`) and escape sequences (`\n`, `\uXXXX`) as distinct kinds.
 *
 * Anything it can't resolve cleanly (a member/top-level property read, an unresolved name) is left to the
 * lexical layer — the highlighter never guesses, so it never miscolors. Polls [EngineCancellation] between
 * nodes so completion can preempt the pass; the host retries.
 */
class KotlinSemanticHighlighter(
    private val parsedFor: (VirtualFile) -> KotlinParsedFile?,
    private val resolverFor: (KotlinParsedFile) -> KotlinResolver,
    private val refresh: () -> Unit,
    /** Content stamp of OTHER source files (cross-file deps); a change invalidates the per-declaration cache,
     *  since a reference here can resolve to — and recolor against — a symbol edited in another file. */
    private val externalStampFor: (String) -> Long = { 0L },
) : SemanticHighlightService {

    // Per-top-level-declaration token cache (see [IncrementalDecls]). An edit re-colors only the changed
    // declaration and reuses every other declaration's tokens, re-anchored to its shifted offset — so a
    // keystroke no longer re-resolves the WHOLE file. One instance per analyzer (this cache is its state).
    private class DeclTokens(val facts: IncrementalDecls.Facts, val rel: List<SemanticToken>)
    private class Snapshot(
        val imports: IncrementalDecls.Imports,
        val fileText: String,
        val externalStamp: Long,
        val decls: List<DeclTokens>
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    override suspend fun highlight(file: VirtualFile): List<SemanticToken> = KotlinPerf.trace("kt.highlight") {
        val parsed = parsedFor(file) ?: return@trace emptyList()
        KotlinPerf.span("refresh") { refresh() } // cross-file freshness: a decl just typed in another open file resolves here
        val resolver = KotlinPerf.span("resolver") { resolverFor(parsed) }
        val ktFile = parsed.ktFile
        val out = ArrayList<SemanticToken>(256)
        // Non-declaration top-level children (package / imports / file annotations) — cheap, recomputed each
        // time. They precede every declaration in a Kotlin file, so emitting them first keeps document order.
        for (child in ktFile.children) if (child !is KtDeclaration) collectInto(
            child,
            resolver,
            out
        )

        val topDecls = ktFile.declarations
        val curImports = IncrementalDecls.importsOf(ktFile)
        val curFileText = ktFile.text
        val externalStamp = externalStampFor(file.path)
        val prev = cache[file.path]?.takeIf { it.externalStamp == externalStamp }
        // Recompute only the declarations this edit can affect (changed ones + dependents of a signature/import
        // change); reuse the rest re-anchored. Same dependency plan the diagnostics pass uses (see IncrementalDecls).
        val plan = KotlinPerf.span("scopeCheck") { IncrementalDecls.plan(prev?.decls?.map { it.facts }, prev?.imports, prev?.fileText, topDecls, curImports, curFileText) }
        val recompute: Set<Int>? = (plan as? IncrementalDecls.Plan.Partial)?.recompute
        val newEntries = ArrayList<DeclTokens>(topDecls.size)
        KotlinPerf.span("walk") { for ((i, d) in topDecls.withIndex()) {
            val base = d.textRange.startOffset
            if (recompute != null && i !in recompute) {
                val cached = prev!!.decls[i] // unaffected → reuse this declaration's tokens, re-anchored
                cached.rel.forEach { out += shift(it, base) }
                newEntries += cached
            } else {
                val abs = ArrayList<SemanticToken>()
                collectInto(d, resolver, abs)
                out += abs
                newEntries += DeclTokens(IncrementalDecls.factsOf(d), abs.map { shift(it, -base) })
            }
        } }
        cache[file.path] = Snapshot(curImports, curFileText, externalStamp, newEntries)
        return@trace out
    }

    /** Shift a token's range by [delta] (relative⇄absolute re-anchoring; [SemanticToken] has no copy()). */
    private fun shift(t: SemanticToken, delta: Int): SemanticToken =
        SemanticToken(TextRange(t.range.start + delta, t.range.end + delta), t.kind, t.modifiers)

    /** Walk [root]'s subtree depth-first, emitting a [SemanticToken] per classifiable identifier into [out] —
     *  the same classification the whole-file pass used, now scoped to a subtree so it runs per declaration. */
    private fun collectInto(
        root: PsiElement,
        resolver: KotlinResolver,
        out: MutableList<SemanticToken>
    ) {
        var seen = 0
        fun emit(
            range: com.intellij.openapi.util.TextRange?,
            kind: HighlightKind,
            mods: Set<HighlightModifier> = emptySet()
        ) {
            if (range == null) return
            out += SemanticToken(TextRange(range.startOffset, range.endOffset), kind, mods)
        }

        fun walk(psi: PsiElement) {
            if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
            when (psi) {
                is KtObjectDeclaration ->
                    emit(
                        psi.nameIdentifier?.textRange,
                        HighlightKind.OBJECT,
                        setOf(HighlightModifier.DECLARATION)
                    )

                is KtClass ->
                    emit(
                        psi.nameIdentifier?.textRange,
                        classKind(psi),
                        setOf(HighlightModifier.DECLARATION)
                    )

                is KtNamedFunction ->
                    emit(
                        psi.nameIdentifier?.textRange, HighlightKind.FUNCTION,
                        declMods(HighlightModifier.DECLARATION) + functionMods(psi) + extensionIf(
                            psi.receiverTypeReference != null
                        )
                    )

                is KtProperty -> {
                    val local = psi.parent is KtBlockExpression
                    val const = psi.hasModifier(KtTokens.CONST_KEYWORD)
                    val depr =
                        if (isDeprecatedDecl(psi)) setOf(HighlightModifier.DEPRECATED) else emptySet()
                    emit(
                        psi.nameIdentifier?.textRange,
                        when {
                            const -> HighlightKind.CONSTANT       // `const val` — a compile-time constant
                            local -> HighlightKind.LOCAL_VARIABLE
                            else -> HighlightKind.PROPERTY
                        },
                        declMods(HighlightModifier.DECLARATION) + mutability(psi.isVar) + extensionIf(
                            psi.receiverTypeReference != null
                        ) + depr
                    )
                }

                // A primary-constructor `val`/`var` parameter IS a member property (`data class`/`value class`/
                // regular class) — color it like a property (matching its uses `p.name` and IntelliJ), not a
                // plain parameter. A plain parameter (no `val`/`var`) stays a parameter.
                is KtParameter ->
                    if (psi.hasValOrVar()) emit(
                        psi.nameIdentifier?.textRange, HighlightKind.PROPERTY,
                        declMods(HighlightModifier.DECLARATION) + mutability(psi.isMutable)
                    ) else emit(
                        psi.nameIdentifier?.textRange, HighlightKind.PARAMETER,
                        declMods(HighlightModifier.DECLARATION) + paramMutability(psi)
                    )

                // A destructuring entry `val (a, b) = …` / `for ((k, v) in …)` / `{ (a, b) -> }` — each name is a
                // local binding (never a class member, so always LOCAL_VARIABLE), read-only unless the `var` form.
                is KtDestructuringDeclarationEntry ->
                    emit(
                        psi.nameIdentifier?.textRange,
                        HighlightKind.LOCAL_VARIABLE,
                        declMods(HighlightModifier.DECLARATION) + mutability(psi.isVar)
                    )

                is KtTypeParameter ->
                    emit(
                        psi.nameIdentifier?.textRange,
                        HighlightKind.TYPE_PARAMETER,
                        setOf(HighlightModifier.DECLARATION)
                    )

                // The `@` of an annotation usage (`@Composable`, `@field:Foo`). The annotation NAME is colored
                // by classifyTypeRef when the walk descends into the entry; adding just the `@` here (the entry's
                // range starts at it) makes the whole `@Foo` read as one annotation unit.
                is KtAnnotationEntry -> {
                    val at = psi.textRange.startOffset
                    emit(com.intellij.openapi.util.TextRange(at, at + 1), HighlightKind.ANNOTATION)
                }

                // A Kotlin label: a definition (`loop@ for …`), a jump target (`break@loop`, `continue@loop`,
                // `return@loop`), or a labeled `this`/`super` (`this@Outer`). All share KtExpressionWithLabel; we
                // color its target-label token. Unlabeled `this`/`return`/… have a null target → no token.
                is KtExpressionWithLabel ->
                    emit(psi.getTargetLabel()?.textRange, HighlightKind.LABEL)

                // The three resolution-heavy categories get their own [KotlinPerf] bucket so a slow-highlight
                // trace pins the cost on call-callee resolution vs infix resolution vs member/name inference.
                is KtCallExpression -> KotlinPerf.span("hl.call") { classifyCall(psi, resolver, ::emit) }
                is KtBinaryExpression -> KotlinPerf.span("hl.infix") { classifyInfix(psi, resolver, ::emit) }
                // A named-argument label (`foo(name = x)`) is a parameter reference — color it like a parameter.
                is KtValueArgumentName -> emit(
                    psi.referenceExpression.textRange,
                    HighlightKind.PARAMETER,
                    emptySet()
                )

                is KtUserType -> classifyTypeRef(psi, ::emit)
                is KtNameReferenceExpression -> KotlinPerf.span("hl.ref") { classifyReference(psi, resolver, ::emit) }
                is KtStringTemplateExpression -> classifyStringTemplate(psi, ::emit)
                else -> {}
            }
            var c = psi.firstChild
            while (c != null) {
                walk(c); c = c.nextSibling
            }
        }
        walk(root)
    }

    /** Color a string literal's non-literal parts: escape sequences (`\n`, `\$`, `\uXXXX`) and the interpolation
     *  delimiters (`$` of `$name`, `${`/`}` of `${expr}`). Every Kotlin string is a template expression, so this
     *  also covers escapes in plain strings. The interpolated EXPRESSION itself (`name`, `p.x`) is a normal PSI
     *  subtree the main walk descends into and classifies — so we emit only the delimiters here, never the value. */
    private fun classifyStringTemplate(
        psi: KtStringTemplateExpression,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        for (entry in psi.entries) {
            if (entry is KtEscapeStringTemplateEntry) {
                emit(entry.textRange, HighlightKind.STRING_ESCAPE, emptySet())
                continue
            }
            // A `$name` / `${…}` entry: color just its delimiter leaf tokens (identified by element type, so an
            // unclosed `${` while typing simply has no end token to color — never a stray brace). A literal-text
            // entry has none of these tokens, so nothing is emitted for it (the lexical string color shows through).
            var child = entry.firstChild
            while (child != null) {
                when (child.node.elementType) {
                    KtTokens.SHORT_TEMPLATE_ENTRY_START,
                    KtTokens.LONG_TEMPLATE_ENTRY_START,
                    KtTokens.LONG_TEMPLATE_ENTRY_END ->
                        emit(child.textRange, HighlightKind.STRING_TEMPLATE_ENTRY, emptySet())
                    in KtTokens.KEYWORDS -> {
                        emit(child.textRange, HighlightKind.KEYWORD, emptySet())
                    }
                    else -> {}
                }
                child = child.nextSibling
            }
        }
    }

    private fun classKind(c: KtClass): HighlightKind = when {
        c.isInterface() -> HighlightKind.INTERFACE
        c.isEnum() -> HighlightKind.ENUM
        c.isAnnotation() -> HighlightKind.ANNOTATION
        else -> HighlightKind.CLASS
    }

    private fun functionMods(fn: KtNamedFunction): Set<HighlightModifier> {
        val m = HashSet<HighlightModifier>(3)
        if (fn.annotationEntries.any { it.shortName?.asString() == "Composable" }) m += HighlightModifier.COMPOSABLE
        if (fn.hasModifier(KtTokens.SUSPEND_KEYWORD)) m += HighlightModifier.SUSPEND
        if (isDeprecatedDecl(fn)) m += HighlightModifier.DEPRECATED
        return m
    }

    /** A declaration carrying `@Deprecated` (by annotation simple name) — its own name renders struck through. */
    private fun isDeprecatedDecl(d: KtAnnotated): Boolean =
        d.annotationEntries.any { it.shortName?.asString() == "Deprecated" }

    private fun declMods(vararg m: HighlightModifier): Set<HighlightModifier> = m.toSet()
    private fun extensionIf(b: Boolean): Set<HighlightModifier> =
        if (b) setOf(HighlightModifier.EXTENSION) else emptySet()

    private fun mutability(isVar: Boolean): Set<HighlightModifier> =
        setOf(if (isVar) HighlightModifier.MUTABLE else HighlightModifier.READONLY)

    /** A primary-constructor `val`/`var` property param is read-only/mutable; a plain param is neither. */
    private fun paramMutability(p: KtParameter): Set<HighlightModifier> = when {
        p.hasValOrVar() && p.isMutable -> setOf(HighlightModifier.MUTABLE)
        p.hasValOrVar() -> setOf(HighlightModifier.READONLY)
        else -> emptySet()
    }

    /** Classify a call's callee: a resolved function (+ composable/extension/suspend), or a capitalized
     *  constructor invocation. The callee is also a name reference, so [classifyReference] skips call callees. */
    private fun classifyCall(
        call: KtCallExpression,
        resolver: KotlinResolver,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        val sym = resolver.calleeFunctionOf(call)
        if (sym != null) {
            val mods = HashSet<HighlightModifier>(4)
            if (sym.isComposable) mods += HighlightModifier.COMPOSABLE
            if (sym.isExtension) mods += HighlightModifier.EXTENSION
            if (sym.isSuspend) mods += HighlightModifier.SUSPEND
            if (sym.isDeprecated) mods += HighlightModifier.DEPRECATED
            emit(callee.textRange, HighlightKind.METHOD, mods)
            return
        }

        // No function resolved: a capitalized callee is a constructor / object invoke (`Foo(...)`, `Color(...)`).
        if (callee.getReferencedName().firstOrNull()?.isUpperCase() == true) {
            emit(callee.textRange, HighlightKind.CONSTRUCTOR, emptySet())
        }
    }

    /** An infix call (`a combine b`, `0 until n`): color the operator identifier as the resolved function, with
     *  its extension/suspend/composable facts. Operator-token binaries (`+`, `<`) are left to the lexical layer. */
    private fun classifyInfix(
        e: KtBinaryExpression,
        resolver: KotlinResolver,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        if (e.operationToken != KtTokens.IDENTIFIER) return
        val sym = resolver.resolveInfixFunction(e) ?: return
        val mods = HashSet<HighlightModifier>(3)
        if (sym.isComposable) mods += HighlightModifier.COMPOSABLE
        if (sym.isExtension) mods += HighlightModifier.EXTENSION
        if (sym.isSuspend) mods += HighlightModifier.SUSPEND
        emit(e.operationReference.textRange, HighlightKind.METHOD, mods)
    }

    /** A type-position name: an annotation usage (`@Foo`), an in-scope type parameter (`T`), or a class. Only
     *  the LEAF of a qualified type (`a.b.C` → `C`) is colored; the qualifier segments (packages/owners) are
     *  KtUserType children visited separately and skipped here, so a package name isn't miscolored as a class. */
    private fun classifyTypeRef(
        userType: KtUserType,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        if (userType.parent is KtUserType) return // a qualifier segment of an enclosing type
        val ref = userType.referenceExpression ?: return
        val name = ref.getReferencedName()
        val kind = when {
            isAnnotationName(userType) -> HighlightKind.ANNOTATION
            isTypeParameterInScope(userType, name) -> HighlightKind.TYPE_PARAMETER
            else -> HighlightKind.CLASS
        }
        emit(ref.textRange, kind, emptySet())
    }

    /** Whether [userType] is the type of an annotation entry (`@Foo`, `@pkg.Foo`) — i.e. it sits under the
     *  annotation's constructor callee, not in an annotation argument (`@Foo(Bar::class)` keeps `Bar` a class). */
    private fun isAnnotationName(userType: KtUserType): Boolean =
        userType.getStrictParentOfType<KtConstructorCalleeExpression>()?.parent is KtAnnotationEntry

    /** A bare/member reference in value position: locals & parameters (with var/val) from the PSI scope, and now
     *  resolved member reads (`obj.prop`, `Color.RED`, `point.x`) colored as property / field / enum-constant.
     *  Call callees, named-arg labels, type refs, this/super are handled elsewhere. */
    private fun classifyReference(
        ref: KtNameReferenceExpression,
        resolver: KotlinResolver,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        val parent = ref.parent
        if (parent is KtCallExpression && parent.calleeExpression === ref) return // a call callee (classifyCall)
        if (parent is KtUserType || parent is KtValueArgumentName) return
        if (parent is org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel) return // this/super
        if (parent is KtQualifiedExpression && parent.selectorExpression === ref) {
            classifyMemberSelector(ref, parent, resolver, emit); return
        }
        // A callable reference `Person::age` / `String::length` / `::topFun`: color the referenced callable
        // (`age`) like a property/method; a type-denoting receiver (`Person`) reads as a class. An instance
        // receiver (`instance::foo`) falls through to the local/parameter coloring below.
        if (parent is org.jetbrains.kotlin.psi.KtCallableReferenceExpression) {
            if (parent.callableReference === ref) { classifyCallableRef(ref, parent, resolver, emit); return }
            if (parent.receiverExpression === ref && resolver.typeDenotationFqn(ref) != null) {
                emit(ref.textRange, HighlightKind.CLASS, emptySet()); return
            }
        }
        val name = ref.getReferencedName()
        if (name.isEmpty()) return
        val offset = ref.textRange.startOffset
        when (val decl = localOrParamDecl(name, offset, ref)) {
            is KtParameter -> {
                emit(ref.textRange, HighlightKind.PARAMETER, paramMutability(decl)); return
            }

            is KtProperty -> {
                emit(ref.textRange, HighlightKind.LOCAL_VARIABLE, mutability(decl.isVar)); return
            }

            is KtDestructuringDeclarationEntry -> {
                emit(ref.textRange, HighlightKind.LOCAL_VARIABLE, mutability(decl.isVar)); return
            }
        }
        // The implicit lambda parameter `it` (`x.let { it }`, `x.also { it }`) is synthetic — no `KtParameter`,
        // so the PSI scan above misses it. The resolver synthesizes it ONLY when an enclosing no-arg lambda fills
        // a single-value-parameter functional type, so gating on that is sound (it's never a stray `it`). Immutable.
        if (name == "it" && resolver.localsAt(offset)
                .any { it.name == "it" && it.kind == SymbolKind.PARAMETER }
        ) {
            emit(ref.textRange, HighlightKind.PARAMETER, setOf(HighlightModifier.READONLY)); return
        }
        // A bare member read resolved through an implicit receiver — the `this` of an `apply`/`with`/`run` block,
        // an enclosing extension receiver, or the enclosing class (`p.apply { x }`). Colored like the qualified
        // form (`p.x`) would be in [classifyMemberSelector]; a method ref without a call is left to the lexer.
        val member = resolver.implicitReceiverMember(name, offset) ?: return
        when (member.kind) {
            SymbolKind.FIELD -> emit(ref.textRange, HighlightKind.PROPERTY, deprecationMods(member))
            SymbolKind.ENUM_CONSTANT -> emit(
                ref.textRange,
                HighlightKind.ENUM_CONSTANT,
                deprecationMods(member)
            )

            else -> {} // a member / top-level / unresolved name → leave to the lexical layer
        }
    }

    /** A callable reference's referenced callable (`Person::age`, `String::length`, `::topFun`): colored as a
     *  method (function) or property/enum-constant when it resolves off the receiver type or top-level scope.
     *  Left to the lexer when the parse-only model can't resolve it. */
    private fun classifyCallableRef(
        ref: KtNameReferenceExpression,
        cr: org.jetbrains.kotlin.psi.KtCallableReferenceExpression,
        resolver: KotlinResolver,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        val sym = resolver.callableReferenceTarget(cr) ?: return
        when (sym.kind) {
            SymbolKind.METHOD -> {
                val mods = HashSet<HighlightModifier>(4)
                if (sym.isComposable) mods += HighlightModifier.COMPOSABLE
                if (sym.isExtension) mods += HighlightModifier.EXTENSION
                if (sym.isSuspend) mods += HighlightModifier.SUSPEND
                if (sym.isDeprecated) mods += HighlightModifier.DEPRECATED
                emit(ref.textRange, HighlightKind.METHOD, mods)
            }
            SymbolKind.FIELD -> emit(ref.textRange, HighlightKind.PROPERTY, deprecationMods(sym))
            SymbolKind.ENUM_CONSTANT -> emit(ref.textRange, HighlightKind.ENUM_CONSTANT, deprecationMods(sym))
            else -> {}
        }
    }

    /** A member read `receiver.name` (not a call) resolved off the receiver type: an enum constant, else a
     *  property/field. A type/static receiver (`Color.RED`, `Companion.X`) and an instance receiver
     *  (`point.x`) are both handled; an unresolvable receiver/member is left to the lexical layer. */
    private fun classifyMemberSelector(
        ref: KtNameReferenceExpression,
        q: KtQualifiedExpression,
        resolver: KotlinResolver,
        emit: (com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit
    ) {
        val name = ref.getReferencedName()
        if (name.isEmpty()) return
        val receiver = q.receiverExpression
        val typeFqn = resolver.typeDenotationFqn(receiver)
        val member = if (typeFqn != null) {
            if (resolver.enumConstantNames(typeFqn).contains(name)) {
                emit(ref.textRange, HighlightKind.ENUM_CONSTANT, emptySet()); return
            }
            resolver.staticMemberNamed(typeFqn, name)
        } else {
            val inferred = resolver.inferType(receiver) ?: return
            // A bare type-parameter receiver (`t.member` where `t: T`, `<T : Bound>`) highlights its members
            // against the parameter's upper bound — whether or not the inferred `T` is MARKED (a class field's
            // `T` is, a function parameter's isn't). Resolve the bound first, then skip a LEAKED (not-in-scope)
            // type parameter, whose concrete members can't be known.
            val recvType = resolver.receiverForMembers(inferred, receiver.textRange.startOffset) ?: return
            if (recvType.isTypeParameter) return
            resolver.instanceMemberNamed(recvType, name)
        } ?: return
        // A property/field read (a method ref without a call is rare; leave it to the lexical layer).
        if (member.kind == dev.ide.lang.resolve.SymbolKind.FIELD || member.kind == dev.ide.lang.resolve.SymbolKind.ENUM_CONSTANT) {
            val kind =
                if (member.kind == dev.ide.lang.resolve.SymbolKind.ENUM_CONSTANT) HighlightKind.ENUM_CONSTANT else HighlightKind.PROPERTY
            emit(ref.textRange, kind, deprecationMods(member))
        }
    }

    /** The DEPRECATED modifier when [sym] is `@Deprecated` (UI renders strikethrough), else nothing. */
    private fun deprecationMods(sym: dev.ide.lang.kotlin.symbols.KotlinSymbol): Set<HighlightModifier> =
        if (sym.isDeprecated) setOf(HighlightModifier.DEPRECATED) else emptySet()

    /** The nearest local property / parameter named [name] declared before [offset], or null if it resolves
     *  to a class member / top level (the walk stops at the enclosing class). Mirrors the analyzer's lookup. */
    private fun localOrParamDecl(name: String, offset: Int, from: PsiElement): PsiElement? {
        var node: PsiElement? = from.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression ->
                    for (st in node.statements) {
                        if (st is KtProperty && st.name == name && st.textRange.endOffset <= offset) return st
                        // `val (a, b) = …` — a destructuring statement binds each of its entries as a local.
                        if (st is KtDestructuringDeclaration && st.textRange.endOffset <= offset)
                            st.entries.firstOrNull { it.name == name }?.let { return it }
                    }

                is KtFunction -> {
                    node.valueParameters.firstOrNull { it.name == name }?.let { return it }
                    // A lambda destructuring param `{ (a, b) -> }` — the entries live on the parameter.
                    node.valueParameters.forEach { p ->
                        p.destructuringDeclaration?.entries?.firstOrNull { it.name == name }?.let { return it }
                    }
                }

                is KtForExpression -> {
                    node.loopParameter?.takeIf { it.name == name }?.let { return it }
                    // `for ((k, v) in …)` — the loop parameter is itself a destructuring.
                    node.destructuringDeclaration?.entries?.firstOrNull { it.name == name }?.let { return it }
                }

                is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }
                    ?.let { return it }

                is KtClassOrObject -> return null
            }
            node = node.parent
        }
        return null
    }

    /** Whether [name] is declared as a type parameter of an enclosing function/class (`fun <T> …`, `class C<T>`). */
    private fun isTypeParameterInScope(from: PsiElement, name: String): Boolean {
        var owner = from.getParentOfType<KtTypeParameterListOwner>(strict = true)
        while (owner != null) {
            if (owner.typeParameters.any { it.name == name }) return true
            owner = owner.getParentOfType<KtTypeParameterListOwner>(strict = true)
        }
        return false
    }
}
