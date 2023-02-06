package com.tyron.completion.psi.codeInsight;

import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

public class HardCodedDefaultTypesKt {
    public static PsiType getDefaultType(PsiMethod method,
                                         PsiSubstitutor substitutor,
                                         int index,
                                         PsiExpression argument) {
        return null;
    }
}
