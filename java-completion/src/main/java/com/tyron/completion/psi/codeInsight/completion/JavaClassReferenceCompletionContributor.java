//package com.tyron.completion.psi.codeInsight.completion;
//
//import androidx.annotation.Nullable;
//
//import com.tyron.completion.CompletionContributor;
//import com.tyron.completion.CompletionInitializationContext;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.kotlin.com.intellij.openapi.project.DumbAware;
//import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
//import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
//import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference;
//
//public class JavaClassReferenceCompletionContributor extends CompletionContributor implements DumbAware {
//
//    @Override
//    public void duringCompletion(@NotNull CompletionInitializationContext context) {
//        super.duringCompletion(context);
//    }
//
//    @Nullable
//    public static JavaClassReference findJavaClassReference(final PsiFile file, final int offset) {
//        PsiReference reference = file.findReferenceAt(offset);
//        if (reference instanceof PsiMultiReference) {
//            for (final PsiReference psiReference : ((PsiMultiReference)reference).getReferences()) {
//                if (psiReference instanceof JavaClassReference) {
//                    return (JavaClassReference)psiReference;
//                }
//            }
//        }
//        return reference instanceof JavaClassReference ? (JavaClassReference)reference : null;
//    }
//}
