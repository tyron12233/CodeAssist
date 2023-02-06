package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.psi.util.CompletionUtilCoreImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

public class CompletionUtil {

    @Nullable
    public static <T extends PsiElement> T getOriginalElement(@NotNull T psi) {
        return CompletionUtilCoreImpl.getOriginalElement(psi);
    }

    @NotNull
    public static <T extends PsiElement> T getOriginalOrSelf(@NotNull T psi) {
        final T element = getOriginalElement(psi);
        return element == null ? psi : element;
    }
}
