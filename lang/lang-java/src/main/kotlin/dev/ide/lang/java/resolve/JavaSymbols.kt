package dev.ide.lang.java.resolve

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import dev.ide.lang.dom.DomNode
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef

/**
 * Neutral [Symbol] / [TypeRef] adapters over IntelliJ Java PSI. Every semantic answer (kind, type, members,
 * supertypes, assignability) is delegated to the PSI element / [PsiType] itself, so the backend inherits
 * IntelliJ's resolution rather than re-deriving it. [declaringFile] lets [declaration] hand back a neutral
 * [DomNode] when the symbol was declared in the file currently being analyzed (null for classpath / other-file
 * symbols).
 */
class JavaSymbol(
    val psi: PsiElement,
    private val declaringFile: JavaParsedFile? = null,
) : Symbol {

    override val name: String
        get() = (psi as? PsiNamedElement)?.name ?: "<anonymous>"

    override val kind: SymbolKind get() = symbolKindOf(psi)

    override val type: TypeRef?
        get() = when (psi) {
            is PsiMethod -> psi.returnType?.let { JavaTypeRef(it) }
            is PsiVariable -> JavaTypeRef(psi.type)   // field / param / local
            else -> null
        }

    override val owner: Symbol?
        get() {
            var p = psi.parent
            while (p != null && p !is PsiClass && p !is PsiMethod && p !is PsiPackage) p = p.parent
            return p?.let { JavaSymbol(it, declaringFile) }
        }

    override val modifiers: Set<Modifier>
        get() {
            val owner = psi as? PsiModifierListOwner ?: return emptySet()
            val out = LinkedHashSet<Modifier>()
            if (owner.hasModifierProperty(PsiModifier.PUBLIC)) out += Modifier.PUBLIC
            if (owner.hasModifierProperty(PsiModifier.PROTECTED)) out += Modifier.PROTECTED
            if (owner.hasModifierProperty(PsiModifier.PRIVATE)) out += Modifier.PRIVATE
            if (owner.hasModifierProperty(PsiModifier.STATIC)) out += Modifier.STATIC
            if (owner.hasModifierProperty(PsiModifier.FINAL)) out += Modifier.FINAL
            if (owner.hasModifierProperty(PsiModifier.ABSTRACT)) out += Modifier.ABSTRACT
            if (owner.hasModifierProperty(PsiModifier.DEFAULT)) out += Modifier.DEFAULT
            if (owner.hasModifierProperty(PsiModifier.SYNCHRONIZED)) out += Modifier.SYNCHRONIZED
            return out
        }

    override val origin: SymbolOrigin
        get() = SymbolOrigin(fromSource = psi !is PsiCompiledElement, file = null)

    override fun declaration(): DomNode? {
        val f = declaringFile ?: return null
        return if (psi.containingFile === f.javaFile) f.adapt(psi) else null
    }

    override fun documentation(): String? = (psi as? PsiDocCommentOwner)?.docComment?.text

    override fun equals(other: Any?): Boolean = other is JavaSymbol && other.psi === psi
    override fun hashCode(): Int = System.identityHashCode(psi)
}

internal fun symbolKindOf(psi: PsiElement): SymbolKind = when (psi) {
    is PsiTypeParameter -> SymbolKind.TYPE_PARAMETER
    is PsiClass -> when {
        psi.isAnnotationType -> SymbolKind.ANNOTATION_TYPE
        psi.isEnum -> SymbolKind.ENUM
        psi.isInterface -> SymbolKind.INTERFACE
        psi.isRecord -> SymbolKind.RECORD
        else -> SymbolKind.CLASS
    }
    is PsiMethod -> if (psi.isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD
    is PsiEnumConstant -> SymbolKind.ENUM_CONSTANT
    is PsiField -> SymbolKind.FIELD
    is PsiParameter -> SymbolKind.PARAMETER
    is PsiLocalVariable -> SymbolKind.LOCAL_VARIABLE
    is PsiPackage -> SymbolKind.PACKAGE
    else -> SymbolKind.LOCAL_VARIABLE
}

/**
 * A resolved type, backed by a [PsiType]. [members] enumerates inherited fields/methods/inner types via
 * IntelliJ's PSI (`allFields`/`allMethods`/`allInnerClasses`), so `expr.` completion inherits the full
 * supertype walk. Assignability + the supertype graph likewise delegate to the PSI type.
 */
class JavaTypeRef(val psiType: PsiType) : TypeRef {

    private val resolvedClass: PsiClass? get() = (psiType as? PsiClassType)?.resolve()

    // The canonical, fully-qualified type TEXT, WITH type arguments — e.g. `java.util.List<java.lang.String>`
    // (not the raw `java.util.List`). Matches the JDT backend's contract: consumers such as the
    // introduce-variable action's `renderType` shorten the FQNs in place and rely on the generics being here.
    override val qualifiedName: String
        get() = psiType.canonicalText

    override val typeArguments: List<TypeRef>
        get() = (psiType as? PsiClassType)?.parameters?.map { JavaTypeRef(it) } ?: emptyList()

    override fun isAssignableFrom(other: TypeRef): Boolean =
        other is JavaTypeRef && psiType.isAssignableFrom(other.psiType)

    override fun supertypes(): List<TypeRef> = psiType.superTypes.map { JavaTypeRef(it) }

    override fun members(accessibleFrom: Symbol?): List<Symbol> {
        val cls = resolvedClass ?: return emptyList()
        val out = ArrayList<Symbol>()
        cls.allFields.forEach { out += JavaSymbol(it) }
        cls.allMethods.forEach { out += JavaSymbol(it) }
        cls.allInnerClasses.forEach { out += JavaSymbol(it) }
        return out
    }
}
