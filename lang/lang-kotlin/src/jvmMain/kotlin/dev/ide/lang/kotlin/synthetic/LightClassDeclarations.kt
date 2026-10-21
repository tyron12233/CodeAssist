package dev.ide.lang.kotlin.synthetic

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
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
    fun offsetOf(fileName: String, text: CharSequence, classFqn: String, member: String?): Int {
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
        }
        for (name in candidates) {
            named.firstOrNull { it.name == name }?.let { return nameOffset(it) }
            cls?.primaryConstructorParameters?.firstOrNull { it.hasValOrVar() && it.name == name }
                ?.let { return nameOffset(it) }
        }
        return classOffset
    }

    private fun nameOffset(e: KtElement): Int =
        (e as? KtNamedDeclaration)?.nameIdentifier?.textOffset
            ?: (e as? KtClassOrObject)?.nameIdentifier?.textOffset
            ?: e.textOffset
}
