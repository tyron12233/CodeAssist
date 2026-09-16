package dev.ide.kotlin.syntax

import com.intellij.psi.tree.IElementType as CompilerElementType
import dev.ide.kotlin.syntax.tree.IElementType as OurElementType

/**
 * The bridge the differential suites compare across: a name for every element type, on both sides.
 *
 * Names come from FIELD names by reflection, never from `toString()`. The compiler's debug names are not
 * always its field names (its float literal token is called `FLOAT_CONSTANT`, for one), and a comparison
 * built on them would report differences that are only spelling. Field names are what both vocabularies are
 * actually written in, so that is what the oracle compares.
 */
object CompilerVocabulary {

    /** The compiler's token instances, by the field name that declares them. */
    val compilerTokenNames: Map<CompilerElementType, Set<String>> by lazy {
        staticNames("org.jetbrains.kotlin.lexer.KtTokens")
    }

    /** The compiler's element instances, by field name. */
    val compilerNodeNames: Map<CompilerElementType, Set<String>> by lazy {
        staticNames("org.jetbrains.kotlin.KtNodeTypes")
    }

    /** Ours, by field name. Kotlin objects hold their properties as instance fields on the singleton. */
    val ourTokenNames: Map<OurElementType, Set<String>> by lazy {
        instanceNames(dev.ide.kotlin.syntax.lexer.KtTokens)
    }

    val ourNodeNames: Map<OurElementType, Set<String>> by lazy {
        instanceNames(KtNodeTypes)
    }

    /** Every name our vocabulary declares, whichever instance it points at. */
    val ourTokenFieldNames: Set<String> by lazy { fieldNames(dev.ide.kotlin.syntax.lexer.KtTokens) }

    val ourNodeFieldNames: Set<String> by lazy { fieldNames(KtNodeTypes) }

    /** Name a compiler token for comparison, falling back to its debug name when it is not in KtTokens. */
    fun nameOf(type: CompilerElementType?): String =
        if (type == null) "<null>" else compilerTokenNames[type]?.minOrNull() ?: type.toString()

    /** Name one of ours the same way. */
    fun nameOf(type: OurElementType?): String =
        if (type == null) "<null>" else ourTokenNames[type]?.minOrNull() ?: type.debugName

    private fun staticNames(className: String): Map<CompilerElementType, Set<String>> {
        val owner = Class.forName(className)
        val result = HashMap<CompilerElementType, MutableSet<String>>()
        for (field in owner.fields) {
            val value = runCatching { field.get(null) }.getOrNull() as? CompilerElementType ?: continue
            result.getOrPut(value) { linkedSetOf() }.add(field.name)
        }
        return result
    }

    private fun instanceNames(owner: Any): Map<OurElementType, Set<String>> {
        val result = HashMap<OurElementType, MutableSet<String>>()
        for (field in owner::class.java.declaredFields) {
            field.isAccessible = true
            val value = runCatching { field.get(owner) }.getOrNull() as? OurElementType ?: continue
            result.getOrPut(value) { linkedSetOf() }.add(field.name)
        }
        return result
    }

    private fun fieldNames(owner: Any): Set<String> =
        owner::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            val value = runCatching { field.get(owner) }.getOrNull()
            if (value is OurElementType || value is dev.ide.kotlin.syntax.tree.TokenSet) field.name else null
        }.toSet()
}
