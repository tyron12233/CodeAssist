//package com.tyron.kotlin.completion
//
//import com.tyron.completion.model.CompletionList
//import org.jetbrains.kotlin.com.intellij.psi.PsiElement
//import org.jetbrains.kotlin.psi.KtFile
//
//class CompletionSessionConfiguration(
//    val useBetterPrefixMatcherForNonImportedClasses: Boolean = true,
//    val nonAccessibleDeclarations: Boolean = true,
//    val javaGettersAndSetters: Boolean = true,
//    val javaClassesNotToBeUsed: Boolean = true,
//    val staticMembers: Boolean = true,
//    val dataClassComponentFunctions: Boolean = true
//)
//
//class CompletionSession(
//    protected val configuration: CompletionSessionConfiguration,
//
//    protected val position: PsiElement,
//
//    completionListBuilder: CompletionList.Builder
//) {
//
//    protected val file = position.containingFile as KtFile
//    protected val resolutionFacade
//}