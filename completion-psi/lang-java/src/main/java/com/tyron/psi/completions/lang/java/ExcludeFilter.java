package com.tyron.psi.completions.lang.java;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;

/**
 * @author peter
 */
class ExcludeFilter implements ElementFilter {
    private final PsiElement myExcluded;

    ExcludeFilter(@NotNull PsiVariable excluded) {
        myExcluded = excluded;
    }

    @Override
    public boolean isAcceptable(Object element, @Nullable PsiElement context) {
        return element != myExcluded;
    }

    @Override
    public boolean isClassAcceptable(Class hintClass) {
        return true;
    }
}
