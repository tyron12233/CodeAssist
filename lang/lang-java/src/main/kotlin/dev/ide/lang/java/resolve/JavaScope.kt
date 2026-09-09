package dev.ide.lang.java.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPatternVariable
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The lexical scope at a position: every name Java lets you write bare there, innermost declaration first.
 *
 * The walk goes from [position] outward to the file, collecting what each enclosing element declares: method
 * and lambda parameters, locals (declaration statements, `for` / `foreach` / `catch` / try-with-resources
 * variables, `instanceof` and `switch` pattern variables), type parameters, the enclosing types' members
 * (inherited ones included), and the file's static imports. Innermost-first ordering makes the natural
 * shadowing fall out: a local named like a field is reached first.
 *
 * Two accuracy gates match the compiler closely enough for completion and bare-name resolution: a member of an
 * enclosing type is offered only if [JavaPsiFacade.getResolveHelper] says it is accessible from [position]
 * (so a supertype's `private` members stay hidden), and inside a `static` method / initializer / field
 * initializer only static members are offered.
 */
class JavaScope(
    private val position: PsiElement?,
    private val offset: Int,
    private val declaringFile: JavaParsedFile?,
    private val facade: JavaPsiFacade,
    private val project: Project,
) : Scope {

    override val enclosing: Scope? = null

    override fun symbols(filter: SymbolFilter): List<Symbol> = applyFilter(walk { true }, filter)

    /**
     * The scope's symbols whose name satisfies [accept], the completion path's entry point. The predicate is
     * pushed down to where each name becomes known, ahead of the per-member accessibility check and the
     * override-collapse key, so typing a prefix does not pay for the enclosing hierarchy's full member set on
     * every keystroke (an Android `Activity` subclass has well over a thousand inherited members).
     */
    fun symbolsMatching(accept: (String) -> Boolean): List<Symbol> = walk(accept)

    private fun walk(accept: (String) -> Boolean): List<Symbol> {
        val out = NameFilteredSink(accept)
        val staticContext = inStaticContext()
        var child: PsiElement? = null
        var e: PsiElement? = position
        while (e != null) {
            collect(e, child, staticContext, out)
            child = e
            e = e.parent
        }
        return out.symbols()
    }

    /** Accumulates the walk's symbols, dropping any whose name the caller isn't interested in. */
    private inner class NameFilteredSink(private val accept: (String) -> Boolean) {
        private val out = LinkedHashSet<Symbol>()
        fun add(psi: PsiElement) {
            val name = (psi as? PsiNamedElement)?.name ?: return
            if (accept(name)) out += symbol(psi)
        }
        fun wants(name: String?): Boolean = name != null && accept(name)
        fun symbols(): List<Symbol> = out.toList()
    }

    /** Everything [e] declares that is visible at [position]; [child] is the branch the walk came up through
     *  (a construct whose declarations only reach some of its children consults it). */
    private fun collect(e: PsiElement, child: PsiElement?, staticContext: Boolean, out: NameFilteredSink) {
        when (e) {
            is PsiMethod -> {
                e.parameterList.parameters.forEach { out.add(it) }
                typeParameters(e, out)
            }

            // `x -> …` / `(a, b) -> …`: the parameters are declared by the lambda, not by any method.
            is PsiLambdaExpression -> e.parameterList.parameters.forEach { out.add(it) }

            is PsiClass -> {
                typeParameters(e, out)
                inheritedMembers(e, staticContext, out)
                recordComponents(e, staticContext, out)
            }

            is PsiCodeBlock -> declaredBefore(e, out)

            // `for (int i = 0; …)`: the loop variable is declared by the statement, not by its body block.
            is PsiForStatement -> (e.initialization as? PsiDeclarationStatement)?.let { declarations(it, out) }

            is PsiForeachStatement -> out.add(e.iterationParameter)

            is PsiCatchSection -> e.parameter?.let { out.add(it) }

            // try-with-resources variables are in scope in the resource list itself and in the try block.
            is PsiTryStatement -> e.resourceList?.let { list ->
                if (child !== e.finallyBlock && child !is PsiCatchSection) {
                    list.filterIsInstance<PsiVariable>().forEach { out.add(it) }
                }
            }

            // `if (o instanceof String s)`: `s` is in scope in the then-branch (and in the condition's own
            // right-hand operands, which are inside the condition subtree the walk already passed through).
            is PsiIfStatement -> if (child === e.thenBranch) patternVariables(e.condition, out)
            is PsiWhileStatement -> if (child === e.body) patternVariables(e.condition, out)
            is PsiDoWhileStatement -> if (child === e.body) patternVariables(e.condition, out)
            is PsiConditionalExpression -> if (child === e.thenExpression) patternVariables(e.condition, out)

            // `case String s ->` / `case Point(int x, int y)`: the label's pattern variables.
            is PsiSwitchLabelStatementBase -> patternVariables(e, out)

            is PsiJavaFile -> staticImports(e, out)
        }
    }

    /**
     * The members of [cls] and its supertypes that are nameable bare here, most-derived first, so an inherited
     * member completes like the compiler allows (`findViewById` inside an Activity subclass). Overrides and
     * shadowed fields collapse to the one declaration actually reached.
     *
     * Walks the supertype graph reading each class's OWN declarations rather than calling
     * `PsiClass.getAllMethods()`/`getAllFields()`, which build a whole-hierarchy member map and cache it on the
     * class. This runs on every keystroke at a bare-name position, so it keeps nothing past the call.
     */
    private fun inheritedMembers(cls: PsiClass, staticContext: Boolean, out: NameFilteredSink) {
        val fieldNames = HashSet<String>()
        val methodKeys = HashSet<String>()
        forEachInHierarchy(cls) { c ->
            for (f in c.fields) {
                if (out.wants(f.name) && fieldNames.add(f.name) && memberVisible(f, staticContext)) out.add(f)
            }
            for (m in c.methods) {
                if (m.isConstructor || !out.wants(m.name)) continue
                if (methodKeys.add(JavaOverrides.enumerationKey(m)) && memberVisible(m, staticContext)) out.add(m)
            }
            for (n in c.innerClasses) {
                if (out.wants(n.name) && memberVisible(n, staticContext)) out.add(n)
            }
        }
    }

    /** Apply [action] to [cls] and every supertype, breadth-first (so most-derived first) and once each. */
    private inline fun forEachInHierarchy(cls: PsiClass, action: (PsiClass) -> Unit) {
        val visited = Collections.newSetFromMap(IdentityHashMap<PsiClass, Boolean>())
        var frontier = listOf(cls)
        while (frontier.isNotEmpty()) {
            val next = ArrayList<PsiClass>()
            for (c in frontier) {
                if (!visited.add(c)) continue
                action(c)
                next += c.supers
            }
            frontier = next
        }
    }

    /** Locals declared in [block] before the caret (a local isn't in scope in its own initializer). */
    private fun declaredBefore(block: PsiCodeBlock, out: NameFilteredSink) {
        for (st in block.statements) {
            if (st.textRange.startOffset > offset) break
            if (st is PsiDeclarationStatement) declarations(st, out)
            // A `case String s ->` label introduces its pattern variables for the rest of the switch block.
            if (st is PsiSwitchLabelStatementBase && st.textRange.endOffset <= offset) patternVariables(st, out)
        }
    }

    private fun declarations(st: PsiDeclarationStatement, out: NameFilteredSink) {
        st.declaredElements.forEach { d ->
            when (d) {
                is PsiLocalVariable -> if (d.textRange.endOffset <= offset) out.add(d)
                is PsiClass -> out.add(d) // a local class
                else -> {}
            }
        }
    }

    /** The pattern variables `element` binds (`instanceof T t`, `case T t`, record deconstruction components). */
    private fun patternVariables(element: PsiElement?, out: NameFilteredSink) {
        if (element == null) return
        PsiTreeUtil.findChildrenOfType(element, PsiPatternVariable::class.java).forEach { out.add(it) }
    }

    private fun typeParameters(owner: PsiTypeParameterListOwner, out: NameFilteredSink) {
        owner.typeParameters.forEach { out.add(it) }
    }

    /** A record's components are nameable bare inside its own body (they back its implicit fields, and each
     *  method body may reference them). Instance state, so hidden in a static context; only the record's OWN
     *  components (a record is final and inherits none). This is the fallback for when record augmentation is
     *  off ([dev.ide.lang.java.env.JavaRecordSupport]): when it is ON, the real backing field is already offered
     *  by [inheritedMembers], so a component is skipped when a same-named field exists to avoid a duplicate. */
    private fun recordComponents(cls: PsiClass, staticContext: Boolean, out: NameFilteredSink) {
        if (!cls.isRecord || staticContext) return
        cls.recordComponents.forEach { rc ->
            val name = rc.name
            if (out.wants(name) && cls.fields.none { it.name == name }) out.add(rc)
        }
    }

    /** Static-import targets: `import static p.C.m;` brings in `m`, `import static p.C.*;` all of C's statics. */
    private fun staticImports(file: PsiJavaFile, out: NameFilteredSink) {
        val list = file.importList ?: return
        for (imp in list.importStaticStatements) {
            val importedName = imp.referenceName
            if (!imp.isOnDemand && !out.wants(importedName)) continue
            val target = imp.resolveTargetClass() ?: continue
            fun wanted(name: String?, m: PsiMember): Boolean =
                m.hasModifierProperty(PsiModifier.STATIC) && (imp.isOnDemand || name == importedName) && out.wants(name)
            forEachInHierarchy(target) { c ->
                c.fields.forEach { if (wanted(it.name, it)) out.add(it) }
                c.methods.forEach { if (!it.isConstructor && wanted(it.name, it)) out.add(it) }
            }
        }
    }

    /** Whether a member of an enclosing type is writable bare here: accessible from [position], and static
     *  when the caret sits in a static method / initializer / field initializer. */
    private fun memberVisible(member: PsiMember, staticContext: Boolean): Boolean {
        if (staticContext && !member.hasModifierProperty(PsiModifier.STATIC)) return false
        // A public member inherited into this hierarchy is always nameable here; skipping the resolve-helper
        // call for the common case keeps the enclosing-type sweep cheap.
        if (member.hasModifierProperty(PsiModifier.PUBLIC)) return true
        val place = position ?: return true
        return runCatching { facade.resolveHelper.isAccessible(member, place, null) }.getOrDefault(true)
    }

    /** True when the caret is inside a `static` method, a static initializer, or a static field's initializer,
     *  where instance members can't be named without a receiver. */
    private fun inStaticContext(): Boolean {
        var e: PsiElement? = position
        while (e != null && e !is PsiClass) {
            when (e) {
                is PsiMethod -> return e.hasModifierProperty(PsiModifier.STATIC)
                is PsiClassInitializer -> return e.hasModifierProperty(PsiModifier.STATIC)
                is PsiField -> return e.hasModifierProperty(PsiModifier.STATIC)
                is PsiLambdaExpression -> {} // a lambda inherits its enclosing context, keep walking
                else -> {}
            }
            e = e.parent
        }
        return false
    }

    private fun symbol(psi: PsiElement): Symbol = JavaSymbol(psi, declaringFile)

    override fun resolve(name: String): ResolveResult {
        // Same walk, with the name pushed down so resolution doesn't materialise the whole scope to find one.
        symbolsMatching { it == name }.firstOrNull()?.let { return ResolveResult.Resolved(it) }
        // Fall back to a type resolvable by name (simple names resolve against the file's imports/package
        // through the facade's project scope; qualified names resolve directly). `findClass` can LAZILY parse
        // the type's source file (a `buildTree`), so it holds the one global parse lock — that must never run
        // concurrently with the background index build on 32-bit ART (native SIGSEGV). See [IntellijPsiHost].
        dev.ide.psi.IntellijPsiHost.withParseLock { facade.findClass(name, GlobalSearchScope.allScope(project)) }
            ?.let { return ResolveResult.Resolved(JavaSymbol(it, declaringFile)) }
        return ResolveResult.Unresolved
    }

    private fun applyFilter(symbols: List<Symbol>, filter: SymbolFilter): List<Symbol> {
        if (filter == SymbolFilter.ALL) return symbols
        val kinds = filter.kinds
        return symbols.filter { s ->
            (kinds == null || s.kind in kinds) &&
                (!filter.staticOnly || dev.ide.lang.resolve.Modifier.STATIC in s.modifiers) &&
                (!filter.instanceOnly || dev.ide.lang.resolve.Modifier.STATIC !in s.modifiers)
        }
    }
}
