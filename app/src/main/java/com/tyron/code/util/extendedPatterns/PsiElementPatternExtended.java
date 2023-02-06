package com.tyron.code.util.extendedPatterns;

import org.jetbrains.kotlin.com.intellij.patterns.PsiElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.TreeElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

public class PsiElementPatternExtended <T extends PsiElement, Self extends PsiElementPattern<T, Self>> extends TreeElementPattern<PsiElement, T, Self> {

    protected PsiElementPatternExtended(Class<T> aClass) {
        super(aClass);
    }

    @Override
    protected PsiElement getParent(PsiElement psiElement) {
        return null;
    }
}
