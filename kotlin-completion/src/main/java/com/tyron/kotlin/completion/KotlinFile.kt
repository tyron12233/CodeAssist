package com.tyron.kotlin.completion

import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.CharsetToolkit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileFactoryImpl
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

class KotlinFile(val name: String, val kotlinFile: KtFile) {

    fun elementAt(line: Int, character: Int): PsiElement? = kotlinFile.findElementAt(offsetFor(line, character))?.let { expressionFor(it) }

    fun insert(content: String, atLine: Int, atCharacter: Int): KotlinFile {
        val caretPositionOffset = offsetFor(atLine, atCharacter)
        return if (caretPositionOffset != 0) {
            from(kotlinFile.project, kotlinFile.name,
                content = StringBuilder(kotlinFile.text.substring(0, caretPositionOffset))
                    .append(content)
                    .append(kotlinFile.text.substring(caretPositionOffset)).toString()
            )
        } else this
    }

    private fun offsetFor(line: Int, character: Int) = (kotlinFile.viewProvider.document?.getLineStartOffset(line) ?: 0) + character

    private tailrec fun expressionFor(element: PsiElement): PsiElement =
        if (element is KtExpression) element else expressionFor(element.parent)

    companion object {
        fun from(project: Project, name: String, content: String) =
            KotlinFile(name, (PsiFileFactory.getInstance(project) as PsiFileFactoryImpl)
                .trySetupPsiForFile(
                    LightVirtualFile(
                        if (name.endsWith(".kt")) name else "$name.kt",
                        KotlinLanguage.INSTANCE,
                        content
                    ).apply { charset = CharsetToolkit.UTF8_CHARSET },
                    KotlinLanguage.INSTANCE, true, false
                ) as KtFile
            )
    }
}