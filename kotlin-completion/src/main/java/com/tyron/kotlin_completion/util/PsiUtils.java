package com.tyron.kotlin_completion.util;

import com.tyron.completion.progress.ProgressManager;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.psiUtil.PsiUtilsKt;
import org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt;
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope;
import org.jetbrains.kotlin.resolve.scopes.LexicalScope;


import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;
import org.jetbrains.kotlin.resolve.scopes.utils.ScopeUtilsKt;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.typeUtil.TypeUtilsKt;

public class PsiUtils {

    public static <Find> Find findParent(PsiElement element, Class<Find> find) {
        ProgressManager.checkCanceled();
        Sequence<PsiElement> parentsWithSelf = PsiUtilsKt.getParentsWithSelf(element);
        Sequence<Find> sequence = SequencesKt.filterIsInstance(parentsWithSelf, find);
        return SequencesKt.firstOrNull(sequence);
    }

    public static Sequence<PsiElement> getParentsWithSelf(PsiElement element) {
        return PsiUtilsKt.getParentsWithSelf(element);
    }

    public static Sequence<HierarchicalScope> getParentsWithSelf(LexicalScope scope) {
        return ScopeUtilsKt.getParentsWithSelf(scope);
    }

    public static KotlinType replaceArgumentsWithStarProjections(KotlinType type) {
        return TypeUtilsKt.replaceArgumentsWithStarProjections(type);
    }

    public static int getStartOffset(KtElement element) {
        return PsiUtilsKt.getStartOffset(element);
    }
    public static int getEndOffset(KtElement element) {
        return PsiUtilsKt.getEndOffset(element);
    }

    public static Sequence<DeclarationDescriptor> getParentsWithSelf(DeclarationDescriptor d) {
        ProgressManager.checkCanceled();
        return DescriptorUtilsKt.getParentsWithSelf(d);
    }

    public static FqName getFqNameSafe(DeclarationDescriptor d) {
        ProgressManager.checkCanceled();
        return DescriptorUtilsKt.getFqNameSafe(d);
    }

}
