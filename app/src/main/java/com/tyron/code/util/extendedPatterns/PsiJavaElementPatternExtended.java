package com.tyron.code.util.extendedPatterns;

import org.jetbrains.kotlin.com.intellij.patterns.PsiElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PsiJavaElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

public class PsiJavaElementPatternExtended<T extends PsiElement,
        Self extends PsiJavaElementPatternExtended<T, Self>> extends PsiJavaElementPattern<T, Self> {

    protected PsiJavaElementPatternExtended(Class<T> aClass) {
        super(aClass);
    }
}
