package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.platform.ExtensionPoint

/**
 * Where go-to-declaration can take the user: an absolute [path] and a character [offset] in it, with the
 * [label] a chooser shows when there is more than one.
 */
class NavigationTarget(val path: String, val offset: Int, val label: String) {
    override fun equals(other: Any?): Boolean =
        other is NavigationTarget && other.path == path && other.offset == offset && other.label == label

    override fun hashCode(): Int = (path.hashCode() * 31 + offset) * 31 + label.hashCode()

    override fun toString(): String = "$label ($path@$offset)"
}

/**
 * Declarations a plugin knows about that the language backends do not: the C++ function behind a Java
 * `native` method, the Java method a `Java_…` C function implements, a key in a generated file.
 *
 * Asked for go-to-declaration on a file in one of [languages] (empty means every language), after the
 * built-in navigation. A provider's targets are offered when the built-in found nothing, or alongside what
 * it found when the user asks for the full list. Return an empty list for anything that is not yours.
 *
 * [target] is the same analysis target diagnostics get, built from the live buffer, so [offset] indexes the
 * text the user is looking at. Registered on [DECLARATION_PROVIDER_EP]. Since SPI 3.1.0.
 */
interface DeclarationProvider {
    val id: String

    val languages: Set<LanguageId> get() = emptySet()

    suspend fun declarations(target: AnalysisTarget, offset: Int): List<NavigationTarget>
}

/** Plugin-contributed go-to-declaration targets; see [DeclarationProvider]. Since SPI 3.1.0. */
val DECLARATION_PROVIDER_EP = ExtensionPoint<DeclarationProvider>("platform.declarationProvider")
