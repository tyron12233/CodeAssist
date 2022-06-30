package com.tyron.kotlin.completion

import com.tyron.builder.project.api.KotlinModule
import com.tyron.editor.Editor
import com.tyron.kotlin.completion.codeassist.getResolutionScope
import com.tyron.kotlin.completion.codeassist.isVisible
import com.tyron.kotlin.completion.core.builder.KotlinPsiManager
import com.tyron.kotlin.completion.core.resolve.KotlinAnalyzer
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtilRt
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import java.io.File
import java.time.Duration
import java.time.Instant

object KotlinCompletionUtils {
    private const val KOTLIN_DUMMY_IDENTIFIER = "KotlinRulezzz"

    fun getReferenceVariants(
        simpleNameExpression: KtSimpleNameExpression,
        nameFilter: (Name) -> Boolean,
        file: File,
        identifierPart: String?
    ): Collection<DeclarationDescriptor> {
        val start = Instant.now()
        val (analysisResult, container) = KotlinAnalyzer.analyzeFile(simpleNameExpression.containingKtFile)

        if (container == null) return emptyList()

        val inDescriptor = simpleNameExpression
            .getReferencedNameElement()
            .getResolutionScope(analysisResult.bindingContext)
            .ownerDescriptor

        val showNonVisibleMembers = true

        val visibilityFilter = { descriptor: DeclarationDescriptor ->
            when (descriptor) {
                is TypeParameterDescriptor -> descriptor.isVisible(inDescriptor)

                is DeclarationDescriptorWithVisibility -> {
                    showNonVisibleMembers || descriptor.isVisible(inDescriptor, analysisResult.bindingContext, simpleNameExpression)
                }

                else -> true
            }
        }

        val collectAll = true//(identifierPart == null || identifierPart.length > 2) || true //!KotlinScriptEnvironment.isScript(file)
        val kind = if (collectAll) DescriptorKindFilter.ALL else DescriptorKindFilter.CALLABLES


//        return KotlinReferenceVariantsHelper (
//            analysisResult.bindingContext,
//            KotlinResolutionFacade(file, container, analysisResult.moduleDescriptor),
//            analysisResult.moduleDescriptor,
//            visibilityFilter).getReferenceVariants (
//            simpleNameExpression, kind, nameFilter)

        try {
            return DescriptorUtils.getAllDescriptors(
                (inDescriptor.containingDeclaration as ClassDescriptor)
                    .unsubstitutedMemberScope
            )
        } finally {
            println("Getting descriptors took ${Duration.between(start, Instant.now()).toMillis()}")
        }
    }

    fun getPsiElement(editor: Editor, identOffset: Int): PsiElement? {
        val sourceCode = editor.content
        val sourceCodeWithMarker = StringBuilder(sourceCode).insert(identOffset, KOTLIN_DUMMY_IDENTIFIER).toString()
        val jetFile: KtFile?
        val file = editor.currentFile
        val module = editor.project!!.getModule(file) as KotlinModule
        if (file != null) {
            jetFile = KotlinPsiManager.parseText(StringUtilRt.convertLineSeparators(sourceCodeWithMarker), file, module)
        } else {
//            KotlinLogger.logError("Failed to retrieve IFile from editor $editor", null)
            return null
        }

        if (jetFile == null) return null

//        val offsetWithoutCR = LineEndUtil.convertCrToDocumentOffset(sourceCodeWithMarker, identOffset, editor.document)
        return jetFile.findElementAt(identOffset)
    }
}