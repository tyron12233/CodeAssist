package dev.ide.lang.java.navigate

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil

/** The go-to actions [JavaNavigation] answers. Usages and related files are the host's (Android resources). */
enum class JavaNavKind { DECLARATION, IMPLEMENTATION, TYPE_DECLARATION, SUPER }

/** Where a navigation target lives, in terms the host can open without PSI. */
sealed interface JavaNavLocation {
    /** In the file navigated from, at [offset] of the text it was parsed from. */
    data class ThisFile(val offset: Int) : JavaNavLocation

    /** A project source file on disk. */
    data class SourceFile(val path: String, val offset: Int) : JavaNavLocation

    /**
     * A class that has no file of its own in the resolution environment: an open editor buffer (resolved
     * from its unsaved text, so [offset] is into that buffer), or a class generated from other source, such
     * as a Kotlin light class or Android `R`. [classFqn] is the class the target is or belongs to, [topLevelFqn]
     * its outermost class, and [member] the member's simple name (null for the class itself or a constructor).
     */
    data class Generated(
        val classFqn: String,
        val topLevelFqn: String,
        val member: String?,
        val offset: Int,
    ) : JavaNavLocation

    /** A compiled class on the classpath. [classFqn] is the outermost class; [member] the member or nested
     *  class to place the caret on. */
    data class Library(val classFqn: String, val member: String?) : JavaNavLocation
}

/** One go-to destination: where it is, a [label] for the target picker, and a [kind] hint for its icon. */
data class JavaNavTarget(val location: JavaNavLocation, val label: String, val kind: String)

/**
 * Go to Declaration / Implementation(s) / Type Declaration / Super for a Java file, on IntelliJ's resolver.
 *
 * The symbol under the caret is either a reference (resolved, or every candidate of an ambiguous call) or the
 * declaration whose name the caret is on. Implementations come from [subtypes] (the host's subtype index,
 * keyed by simple name) and are kept only when the resolver confirms the inheritance. Super uses the
 * hierarchical signature, which is the one hierarchy API that works on ART (`findSuperMethods` does not).
 *
 * Every call must run under the parse lock: resolving can build the tree of another class.
 */
class JavaNavigation(
    private val facade: JavaPsiFacade,
    private val subtypes: (String) -> List<String>,
) {

    private class Caret(val referenced: List<PsiElement>, val declared: PsiElement?, val leaf: PsiElement)

    fun targets(file: PsiJavaFile, offset: Int, kind: JavaNavKind): List<JavaNavTarget> {
        val caret = caretAt(file, offset) ?: return emptyList()
        return targetsFor(kind, caret, file)
    }

    /** Each kind that has at least one target at [offset], with its targets, in [JavaNavKind] order. */
    fun options(file: PsiJavaFile, offset: Int): List<Pair<JavaNavKind, List<JavaNavTarget>>> {
        val caret = caretAt(file, offset) ?: return emptyList()
        return JavaNavKind.entries.mapNotNull { kind ->
            targetsFor(kind, caret, file).takeIf { it.isNotEmpty() }?.let { kind to it }
        }
    }

    /**
     * The declaration of the class [fqn], or of its [member] by simple name (a method or constructor, a field,
     * a nested class, or the getter behind a Kotlin property name). Null for a class that is not source: the
     * caller reaches compiled classes its own way.
     */
    fun declarationOf(fqn: String, member: String?): JavaNavTarget? {
        val cls = facade.findClass(fqn, GlobalSearchScope.allScope(facade.project)) ?: return null
        if (cls is PsiCompiledElement) return null
        val e: PsiElement = member?.let { name ->
            val cap = name.replaceFirstChar { it.uppercaseChar() }
            cls.findMethodsByName(name, false).firstOrNull()
                ?: cls.findFieldByName(name, false)
                ?: cls.findInnerClassByName(name, false)
                ?: cls.findMethodsByName("get$cap", false).firstOrNull()
                ?: cls.findMethodsByName("is$cap", false).firstOrNull()
        } ?: cls
        return target(e, from = null)
    }

    private fun targetsFor(kind: JavaNavKind, caret: Caret, file: PsiJavaFile): List<JavaNavTarget> {
        val elements = when (kind) {
            JavaNavKind.DECLARATION -> caret.referenced
            JavaNavKind.TYPE_DECLARATION -> listOfNotNull(typeDeclaration(symbolOf(caret)))
            JavaNavKind.IMPLEMENTATION -> implementations(symbolOf(caret))
            JavaNavKind.SUPER -> supers(symbolOf(caret) ?: enclosing(caret.leaf))
        }
        return elements.mapNotNull { target(it, file) }.distinctBy { it.location }
    }

    /** The one symbol a type-scoped action is about: the referenced one, else the declaration under the caret. */
    private fun symbolOf(caret: Caret): PsiElement? = caret.referenced.firstOrNull() ?: caret.declared

    private fun caretAt(file: PsiJavaFile, offset: Int): Caret? {
        val len = file.textLength
        if (len == 0) return null
        val at = offset.coerceIn(0, len - 1)
        // A caret just past an identifier (`foo|(`) is still on it.
        val leaf = file.findElementAt(at)?.takeIf { it is PsiIdentifier }
            ?: (if (at > 0) file.findElementAt(at - 1) as? PsiIdentifier else null)
            ?: return file.findElementAt(at)?.let { Caret(emptyList(), null, it) }
        val parent = leaf.parent
        if (parent is PsiNameIdentifierOwner && parent.nameIdentifier === leaf) {
            return Caret(emptyList(), parent, leaf)
        }
        if (parent is PsiJavaCodeReferenceElement && parent.referenceNameElement === leaf) {
            return Caret(referenced(parent), null, leaf)
        }
        return Caret(emptyList(), null, leaf)
    }

    private fun referenced(ref: PsiJavaCodeReferenceElement): List<PsiElement> {
        // `new Foo(...)` goes to the constructor it calls, or the class when it declares none.
        (ref.parent as? PsiNewExpression)?.takeIf { it.classReference === ref }?.let { new ->
            return listOfNotNull(new.resolveConstructor() ?: ref.resolve())
        }
        ref.resolve()?.let { return listOf(it) }
        // No single answer: an overloaded call whose arguments fit none, or several, of the candidates.
        return (ref as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element } ?: emptyList()
    }

    private fun enclosing(leaf: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, PsiClass::class.java)

    private fun typeDeclaration(symbol: PsiElement?): PsiClass? {
        val type: PsiType = when (symbol) {
            is PsiVariable -> symbol.type
            is PsiMethod -> symbol.returnType ?: return null
            else -> return null
        }
        return PsiTypesUtil.getPsiClass(type.deepComponentType)
    }

    private fun implementations(symbol: PsiElement?): List<PsiElement> = when (symbol) {
        is PsiClass -> inheritors(symbol)
        is PsiMethod -> {
            val owner = symbol.containingClass
            if (owner == null || symbol.isConstructor || symbol.hasModifierProperty(PsiModifier.STATIC) ||
                symbol.hasModifierProperty(PsiModifier.PRIVATE)
            ) emptyList()
            else inheritors(owner).mapNotNull { MethodSignatureUtil.findMethodBySuperMethod(it, symbol, false) }
        }
        else -> emptyList()
    }

    private fun inheritors(base: PsiClass): List<PsiClass> {
        if (base.hasModifierProperty(PsiModifier.FINAL)) return emptyList()
        val fqn = base.qualifiedName ?: return emptyList()
        val scope = GlobalSearchScope.allScope(facade.project)
        return subtypes(fqn).asSequence()
            .mapNotNull { facade.findClass(it, scope) }
            .filter { it != base && it.isInheritor(base, true) }
            .take(MAX_IMPLEMENTATIONS)
            .toList()
    }

    private fun supers(symbol: PsiElement?): List<PsiElement> = when (symbol) {
        is PsiMethod -> runCatching { symbol.hierarchicalMethodSignature.superSignatures.map { it.method } }
            .getOrDefault(emptyList())
        is PsiAnonymousClass -> listOfNotNull(symbol.baseClassType.resolve())
        is PsiClass ->
            if (symbol.isInterface) symbol.interfaces.toList()
            else listOfNotNull(symbol.superClass) + symbol.interfaces
        else -> emptyList()
    }

    private fun target(e: PsiElement, from: PsiJavaFile?): JavaNavTarget? {
        val location = location(e, from) ?: return null
        val kind = if (location is JavaNavLocation.Library) "library" else kindOf(e)
        return JavaNavTarget(location, labelOf(e), kind)
    }

    private fun location(e: PsiElement, from: PsiJavaFile?): JavaNavLocation? {
        if (e !is PsiMember && e !is PsiVariable) return null
        val owner = e as? PsiClass ?: PsiTreeUtil.getParentOfType(e, PsiClass::class.java)
        if (e is PsiCompiledElement) {
            val top = outermost(owner ?: return null)
            val member = when {
                e === top -> null
                e is PsiMethod && e.isConstructor -> null
                else -> (e as PsiMember).name
            }
            return JavaNavLocation.Library(top.qualifiedName ?: return null, member)
        }
        val file = e.containingFile ?: return null
        val offset = (e as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: e.textOffset
        if (from != null && (file === from || file.originalFile === from)) return JavaNavLocation.ThisFile(offset)
        val vf = file.virtualFile
        if (vf != null && vf.fileSystem.protocol == "file") return JavaNavLocation.SourceFile(vf.path, offset)
        // A local or parameter is only reached from inside its own file, which is handled above.
        if (owner == null || e is PsiLocalVariable || e is PsiParameter) return null
        val member = if (e is PsiClass || (e is PsiMethod && e.isConstructor)) null else (e as PsiMember).name
        return JavaNavLocation.Generated(
            classFqn = owner.qualifiedName ?: return null,
            topLevelFqn = outermost(owner).qualifiedName ?: return null,
            member = member,
            offset = offset,
        )
    }

    private fun outermost(c: PsiClass): PsiClass {
        var cls = c
        while (true) cls = cls.containingClass ?: return cls
    }

    private fun labelOf(e: PsiElement): String = when (e) {
        is PsiClass -> {
            val fqn = e.qualifiedName
            val pkg = fqn?.substringBeforeLast('.', "").orEmpty()
            if (pkg.isEmpty()) e.name.orEmpty() else "${e.name}  ·  $pkg"
        }
        is PsiMethod -> {
            val params = e.parameterList.parameters.joinToString(", ") { it.type.presentableText }
            "${e.name}($params)" + (e.containingClass?.name?.let { "  ·  $it" } ?: "")
        }
        is PsiField -> e.name + (e.containingClass?.name?.let { "  ·  $it" } ?: "")
        is PsiVariable -> e.name.orEmpty()
        else -> e.text.take(40)
    }

    private fun kindOf(e: PsiElement): String = when (e) {
        is PsiClass -> when {
            e.isAnnotationType -> "annotation_class"
            e.isEnum -> "enum_class"
            e.isInterface -> "interface"
            else -> "class"
        }
        is PsiMethod -> if (e.isConstructor) "constructor" else "method"
        is PsiField -> "field"
        else -> "val"
    }

    private companion object {
        const val MAX_IMPLEMENTATIONS = 100
    }
}
