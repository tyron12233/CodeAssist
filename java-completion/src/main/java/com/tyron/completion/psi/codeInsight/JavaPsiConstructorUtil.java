package com.tyron.completion.psi.codeInsight;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;

public class JavaPsiConstructorUtil {

    private static final @NotNull TokenSet CONSTRUCTOR_CALL_TOKENS = TokenSet.create(JavaTokenType.SUPER_KEYWORD, JavaTokenType.THIS_KEYWORD);


    /**
     * @param call element to check
     * @return true if given element is {@code this} or {@code super} constructor call
     */
    @Contract("null -> false")
    public static boolean isConstructorCall(@Nullable PsiElement call) {
        if (!(call instanceof PsiMethodCallExpression)) return false;
        PsiElement child = ((PsiMethodCallExpression)call).getMethodExpression().getReferenceNameElement();
        return PsiUtil.isJavaToken(child, CONSTRUCTOR_CALL_TOKENS);
    }
}
