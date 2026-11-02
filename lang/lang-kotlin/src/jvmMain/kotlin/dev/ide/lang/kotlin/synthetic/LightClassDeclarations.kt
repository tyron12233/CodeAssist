package dev.ide.lang.kotlin.synthetic

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.lang.kotlin.parse.KotlinParserHost

/**
 * Maps a member of a Kotlin light class (the Java view [KotlinSyntheticClassProvider] renders) back to the
 * Kotlin declaration it came from, so a Java file's go-to lands in the `.kt` source.
 */
object LightClassDeclarations {

    /**
     * The offset of the declaration in the Kotlin file [text] that the light class [classFqn]'s [member]
     * renders. [classFqn] is a Kotlin class or object (a companion is `Outer.Companion`) or, when no class has
     * that name, the file facade (`FooKt`), whose members are the top-level declarations. A null [member] is
     * the class itself. Property accessors (`getName`/`setName`) map to their property, `INSTANCE` and
     * `Companion` to the object. Falls back to the class, then to the start of the file.
     */
    fun offsetOf(
        fileName: String, text: CharSequence, classFqn: String, member: String?,
        paramCount: Int? = null,
        /** The Java parameter types' simple names (`int`, `String`), which tell overloads of one arity apart. */
        paramTypes: List<String>? = null,
    ): Int {
        val file = KotlinParserHost.parse(fileName, text)
        val cls = file.collectDescendantsOfType<KtClassOrObject>().firstOrNull { it.fqName?.asString() == classFqn }
        val classOffset = cls?.let(::nameOffset) ?: 0
        if (member == null || member == "INSTANCE") return classOffset
        val declarations: List<KtDeclaration> = cls?.declarations ?: file.declarations
        val named = declarations.filterIsInstance<KtNamedDeclaration>()
        val candidates = buildList {
            add(member)
            if (member.length > 3 && (member.startsWith("get") || member.startsWith("set"))) {
                add(member.substring(3).replaceFirstChar { it.lowercaseChar() })
            }
            // `setFoo` is also the setter of a property named `isFoo`.
            if (member.length > 3 && member.startsWith("set")) add("is" + member.substring(3))
        }
        for (name in candidates) {
            // Of several overloads, the one Java called: its parameter count is the overload's.
            val sameName = named.filter { it.name == name }
            val sameArity = paramCount?.let { n -> sameName.filter { (it as? KtNamedFunction)?.valueParameters?.size == n } }.orEmpty()
            val overload = sameArity.firstOrNull { fn -> paramTypes != null && typesFit(fn as KtNamedFunction, paramTypes) }
                ?: sameArity.firstOrNull()
            (overload ?: sameName.firstOrNull())?.let { return nameOffset(it) }
            cls?.primaryConstructorParameters?.firstOrNull { it.hasValOrVar() && it.name == name }
                ?.let { return nameOffset(it) }
        }
        return classOffset
    }

    /** Whether [fn]'s parameter types read as the Java [javaTypes] (`Int` as `int`/`Integer`, `Any` as `Object`). */
    private fun typesFit(fn: KtNamedFunction, javaTypes: List<String>): Boolean {
        val params = fn.valueParameters
        if (params.size != javaTypes.size) return false
        return params.indices.all { i ->
            val k = params[i].typeReference?.text?.substringBefore('<')?.removeSuffix("?")?.trim()?.substringAfterLast('.')
                ?: return@all true
            val j = javaTypes[i]
            k == j || j in (KOTLIN_TO_JAVA[k] ?: emptySet()) || (k.endsWith("Array") && j.endsWith("[]"))
        }
    }

    private val KOTLIN_TO_JAVA: Map<String, Set<String>> = mapOf(
        "Int" to setOf("int", "Integer"), "Long" to setOf("long", "Long"), "Short" to setOf("short", "Short"),
        "Byte" to setOf("byte", "Byte"), "Char" to setOf("char", "Character"), "Boolean" to setOf("boolean", "Boolean"),
        "Float" to setOf("float", "Float"), "Double" to setOf("double", "Double"), "Any" to setOf("Object"),
        "Unit" to setOf("void"), "MutableList" to setOf("List"), "MutableMap" to setOf("Map"), "MutableSet" to setOf("Set"),
    )

    private fun nameOffset(e: KtElement): Int =
        (e as? KtNamedDeclaration)?.nameIdentifier?.textOffset
            ?: (e as? KtClassOrObject)?.nameIdentifier?.textOffset
            ?: e.textOffset
}
