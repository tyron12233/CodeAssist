package dev.ide.lang.java.completion

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSwitchBlock
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind

/**
 * Scope-gated Java keyword completion — mirrors lang-kotlin's `KotlinKeywords` `Place` model. Instead of
 * offering the flat reserved-word list everywhere a name is legal, it classifies the caret (a PSI parent walk
 * of the spliced completion tree) into a [Place] and offers only the keywords valid there: modifiers at
 * declaration positions, statement keywords inside a method body, and `return`/`break`/`continue` only where
 * they are actually legal (a method / a loop or switch). Ranked below real symbols via `sortPriority`.
 */
internal object JavaKeywords {

    fun itemsFor(leaf: PsiElement, matches: (String) -> Boolean): List<CompletionItem> =
        keywordsAt(leaf).filter(matches).map { kw ->
            CompletionItem(label = kw, insertText = kw, kind = CompletionItemKind.KEYWORD, sortPriority = 50)
        }

    private enum class Place { TOP_LEVEL, MEMBER, CODE, NONE }

    private fun keywordsAt(leaf: PsiElement): Set<String> = when (placeOf(leaf)) {
        Place.TOP_LEVEL -> TOP_LEVEL
        Place.MEMBER -> MEMBER
        Place.CODE -> buildSet {
            addAll(CODE)
            addAll(PRIMITIVES)
            if (inLoopOrSwitch(leaf)) add("break")
            if (inLoop(leaf)) add("continue")
        }
        Place.NONE -> emptySet()
    }

    /** Classify the caret: inside a method/initializer body ([Place.CODE]), directly in a class body
     *  ([Place.MEMBER]), at file scope ([Place.TOP_LEVEL]), or somewhere no keyword is offered (a type-argument
     *  slot, a reference qualifier). The innermost enclosing structure wins. */
    private fun placeOf(leaf: PsiElement): Place {
        val block = PsiTreeUtil.getParentOfType(leaf, PsiCodeBlock::class.java, false)
        val cls = PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false)
        val file = leaf.containingFile as? PsiJavaFile
        // A code block that sits inside the innermost class (method/initializer body) → statement position.
        if (block != null && (cls == null || PsiTreeUtil.isAncestor(cls, block, false))) return Place.CODE
        if (cls != null) return Place.MEMBER
        if (file != null) return Place.TOP_LEVEL
        return Place.NONE
    }

    /** True when [leaf] is inside a loop body, stopping at a method/lambda boundary (a loop in an outer scope
     *  doesn't make `continue` legal here). */
    private fun inLoop(leaf: PsiElement): Boolean {
        var e: PsiElement? = leaf
        while (e != null) {
            when (e) {
                is PsiForStatement, is PsiForeachStatement, is PsiWhileStatement, is PsiDoWhileStatement -> return true
                is PsiMethod, is PsiLambdaExpression -> return false
            }
            e = e.parent
        }
        return false
    }

    /** True when [leaf] is inside a loop OR a switch — where `break` is legal. */
    private fun inLoopOrSwitch(leaf: PsiElement): Boolean {
        var e: PsiElement? = leaf
        while (e != null) {
            when (e) {
                is PsiForStatement, is PsiForeachStatement, is PsiWhileStatement, is PsiDoWhileStatement,
                is PsiSwitchBlock -> return true
                is PsiMethod, is PsiLambdaExpression -> return false
            }
            e = e.parent
        }
        return false
    }

    private val PRIMITIVES = setOf("boolean", "byte", "char", "double", "float", "int", "long", "short")

    // `break`/`continue` are added by the loop/switch gate above, so they are absent from the base CODE set.
    private val CODE = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "return", "throw", "try", "catch",
        "finally", "synchronized", "assert", "new", "instanceof", "this", "super", "var", "final", "class",
        "true", "false", "null", "yield",
    )
    private val MEMBER = setOf(
        "public", "private", "protected", "static", "final", "abstract", "synchronized", "native", "transient",
        "volatile", "strictfp", "default", "class", "interface", "enum", "record", "void", "this", "super",
    ) + PRIMITIVES
    private val TOP_LEVEL = setOf(
        "package", "import", "public", "final", "abstract", "class", "interface", "enum", "record",
    )
}
