package com.tyron.psi.completions.lang.java.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;

public class JavaPsiConstructorUtil {

    /**
     * @param call element to check
     * @return true if given element is {@code this} or {@code super} constructor call
     */
    @Contract("null -> false")
    public static boolean isConstructorCall(@Nullable PsiElement call) {
        if (!(call instanceof PsiMethodCallExpression)) return false;
        PsiElement child = ((PsiMethodCallExpression)call).getMethodExpression().getReferenceNameElement();
        return child instanceof PsiKeyword && (child.textMatches(PsiKeyword.SUPER) || child.textMatches(PsiKeyword.THIS));
    }

}
