package com.tyron.completion.psi.codeInsight;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariableAccessUtils {
    public static List<PsiReferenceExpression> getVariableReferences(@NotNull PsiVariable variable, @Nullable PsiElement context) {
        if (context == null) return Collections.emptyList();
        List<PsiReferenceExpression> result = new ArrayList<>();
        PsiTreeUtil.processElements(context, e -> {
            if (e instanceof PsiReferenceExpression && ((PsiReferenceExpression)e).isReferenceTo(variable)) {
                result.add((PsiReferenceExpression)e);
            }
            return true;
        });
        return result;
    }
}
