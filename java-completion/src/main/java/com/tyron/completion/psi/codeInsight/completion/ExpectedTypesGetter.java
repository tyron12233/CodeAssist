package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.psi.codeInsight.ExpectedTypeInfo;
import com.tyron.completion.psi.codeInsight.ExpectedTypesProvider;

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
    return extractTypes(ExpectedTypesProvider.getExpectedTypes(expression, true), defaultTypes);
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