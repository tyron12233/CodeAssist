package com.tyron.psi.completions.lang.java.filter.getters;

import com.tyron.psi.completions.lang.java.ExpectedTypeInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

import java.util.HashSet;
import java.util.Set;

public final class ExpectedTypesGetter {
    public static PsiType[] getExpectedTypes(final PsiElement context, boolean defaultTypes) {
        PsiExpression expression = PsiTreeUtil.getContextOfType(context, PsiExpression.class, true);
        if (expression == null) {
            return PsiType.EMPTY_ARRAY;
        }
        throw new UnsupportedOperationException("Not yet implemented");
//        return extractTypes(ExpectedTypesProvider.getExpectedTypes(expression, true), defaultTypes);
    }

    public static PsiType[] extractTypes(ExpectedTypeInfo[] infos, boolean defaultTypes) {
        Set<PsiType> result = new HashSet<>(infos.length);
        for (ExpectedTypeInfo info : infos) {
            final PsiType type = info.getType();
            final PsiType defaultType = info.getDefaultType();
            if (!defaultTypes && !defaultType.equals(type)) {
                result.add(type);
            }
            result.add(defaultType);
        }
        return result.toArray(PsiType.createArray(result.size()));
    }
}