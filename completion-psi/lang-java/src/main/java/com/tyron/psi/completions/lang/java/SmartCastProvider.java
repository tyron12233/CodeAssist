package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionParameters;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiParenthesizedExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

public class SmartCastProvider {

    static boolean inCastContext(CompletionParameters parameters) {
        PsiElement position = parameters.getPosition();
        PsiElement parent = getParenthesisOwner(position);
        if (parent instanceof PsiTypeCastExpression) return true;
        if (parent instanceof PsiParenthesizedExpression) {
            return parameters.getOffset() == position.getTextRange().getStartOffset();
        }
        return false;
    }

    private static PsiElement getParenthesisOwner(PsiElement position) {
        PsiElement lParen = PsiTreeUtil.prevVisibleLeaf(position);
        return lParen == null || !lParen.textMatches("(") ? null : lParen.getParent();
    }
}
