package dev.ide.kotlin.syntax

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.psi.tree.IElementType

/**
 * One name for each element kind, on both sides of the parity comparison.
 *
 * The two vocabularies describe the same grammar with occasionally different spellings. The PSI parser's
 * function element answers to both `FUN` and `FUNCTION`; the multiplatform one declares only `FUNCTION`. Its
 * `fun` keyword answers to both `FUN_KEYWORD` and `FUN_MODIFIER`, where PSI knows only the first. Rendering
 * each side under its own `toString()` therefore reports a naming choice as a parser divergence, and for a
 * while that was most of what this suite "found".
 *
 * So the two vocabularies are PAIRED by field name, and a paired kind is printed under a name they SHARE.
 * Picking, say, the alphabetically first name of each side independently is not enough: the sides would
 * disagree the moment one of them knows an extra alias.
 *
 * A kind with no counterpart keeps its own name, because that is a real asymmetry and worth seeing.
 */
object ElementNames {

    private class Vocabulary {
        val psi = HashMap<IElementType, String>()
        val kmp = HashMap<SyntaxElementType, String>()
    }

    private val vocabulary: Vocabulary by lazy { pair() }

    /** What to print for a PSI element. */
    fun of(type: IElementType): String = vocabulary.psi[type] ?: type.toString()

    /** What to print for a multiplatform element. */
    fun of(type: SyntaxElementType): String = vocabulary.kmp[type] ?: type.toString()

    /** Is this multiplatform kind a TOKEN kind rather than an element kind? */
    fun isTokenKind(type: SyntaxElementType): Boolean = type in tokenKinds

    private val tokenKinds: Set<SyntaxElementType> by lazy {
        objectFieldNames<SyntaxElementType>("org.jetbrains.kotlin.kmp.lexer.KtTokens").keys
    }

    private fun pair(): Vocabulary {
        val psiNames = buildMap<IElementType, MutableSet<String>> {
            mergeIn(staticFieldNames<IElementType>("org.jetbrains.kotlin.KtNodeTypes"))
            mergeIn(staticFieldNames<IElementType>("org.jetbrains.kotlin.lexer.KtTokens"))
            mergeIn(staticFieldNames<IElementType>("com.intellij.psi.TokenType"))
        }
        val kmpNames = buildMap<SyntaxElementType, MutableSet<String>> {
            mergeIn(objectFieldNames<SyntaxElementType>("org.jetbrains.kotlin.kmp.parser.KtNodeTypes"))
            mergeIn(objectFieldNames<SyntaxElementType>("org.jetbrains.kotlin.kmp.lexer.KtTokens"))
            mergeIn(objectFieldNames<SyntaxElementType>("com.intellij.platform.syntax.element.SyntaxTokenTypes"))
        }

        val byName = HashMap<String, SyntaxElementType>()
        for ((type, names) in kmpNames) for (name in names) byName[name] = type

        // Field names alone are not enough: the multiplatform vocabulary RENAMED some kinds (PSI's
        // `FUN_KEYWORD` is `FUN_MODIFIER` there), and a rename looks exactly like two unrelated kinds. What
        // did not change is the token's own text, so that is the second key.
        val byText = HashMap<String, SyntaxElementType>()
        for ((type, _) in kmpNames) byText.putIfAbsent(type.toString(), type)

        val result = Vocabulary()
        for ((psiType, names) in psiNames) {
            val counterpart = names.firstNotNullOfOrNull { byName[it] } ?: byText[psiType.toString()]
            if (counterpart == null) {
                result.psi[psiType] = names.min()
                continue
            }
            // Print under a name the two share where there is one; otherwise under PSI's, because that is
            // the vocabulary the rest of this project already speaks.
            val shared = names.intersect(kmpNames.getValue(counterpart))
            val canonical = if (shared.isNotEmpty()) shared.min() else names.min()
            result.psi[psiType] = canonical
            result.kmp[counterpart] = canonical
        }
        // Whatever the multiplatform side knows and PSI does not keeps its own name.
        for ((type, names) in kmpNames) result.kmp.getOrPut(type) { names.min() }
        return result
    }

    private fun <T : Any> MutableMap<T, MutableSet<String>>.mergeIn(other: Map<T, Set<String>>) {
        for ((key, names) in other) getOrPut(key) { linkedSetOf() }.addAll(names)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> staticFieldNames(className: String): Map<T, Set<String>> {
        val owner = Class.forName(className)
        val result = HashMap<T, MutableSet<String>>()
        for (field in owner.fields) {
            val value = runCatching { field.get(null) }.getOrNull() ?: continue
            if (value !is IElementType) continue
            result.getOrPut(value as T) { linkedSetOf() }.add(field.name)
        }
        return result
    }

    /**
     * A Kotlin `object` keeps its properties as INSTANCE fields, unlike the PSI side's Java interfaces whose
     * fields are static. Some are also exposed through getters only, so both are read.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> objectFieldNames(className: String): Map<T, Set<String>> {
        val owner = Class.forName(className)
        val instance = runCatching { owner.getField("INSTANCE").get(null) }.getOrNull()
        val result = HashMap<T, MutableSet<String>>()

        for (field in owner.declaredFields) {
            field.isAccessible = true
            val value = runCatching { field.get(instance) }.getOrNull() ?: continue
            if (value !is SyntaxElementType) continue
            result.getOrPut(value as T) { linkedSetOf() }.add(field.name)
        }
        for (method in owner.methods) {
            if (method.parameterCount != 0 || !method.name.startsWith("get")) continue
            val value = runCatching { method.invoke(instance) }.getOrNull() ?: continue
            if (value !is SyntaxElementType) continue
            val name = method.name.removePrefix("get").replaceFirstChar { it.lowercase() }
            // Property getters spell the field in camelCase; the fields above already carry the real name,
            // so only add what the field scan missed.
            result.getOrPut(value as T) { linkedSetOf() }.add(name.uppercaseFieldName())
        }
        return result
    }

    /** `getFUN_KEYWORD` -> `FUN_KEYWORD`: the getter lower-cases only the first character. */
    private fun String.uppercaseFieldName(): String =
        if (length > 1 && this[1].isUpperCase()) replaceFirstChar { it.uppercaseChar() } else this
}
