package dev.ide.kotlin.syntax.tree

/**
 * The identity of a node or token kind, mirroring `com.intellij.psi.tree.IElementType`.
 *
 * Identity is the object, never the name: two `IElementType`s with the same [debugName] are different kinds.
 * That is the compiler's contract and the parser relies on it (`tokenType === KtTokens.IDENTIFIER`), so the
 * class deliberately does NOT override `equals`/`hashCode`.
 *
 * The name is called `debugName` for the same reason it is there: it exists so a failing test can print the
 * tree, not so anything can dispatch on it.
 */
open class IElementType(val debugName: String) {
    override fun toString(): String = debugName
}

/**
 * The element types IntelliJ's platform supplies rather than the Kotlin language, mirroring
 * `com.intellij.psi.TokenType`. [WHITE_SPACE] and [BAD_CHARACTER] come off the lexer; [ERROR_ELEMENT] is
 * produced by the parser when it has to report a syntax error at a position.
 */
object TokenType {
    val WHITE_SPACE: IElementType = IElementType("WHITE_SPACE")
    val BAD_CHARACTER: IElementType = IElementType("BAD_CHARACTER")
    val ERROR_ELEMENT: IElementType = IElementType("ERROR_ELEMENT")
}
