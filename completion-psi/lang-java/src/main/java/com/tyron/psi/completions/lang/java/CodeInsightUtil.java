package com.tyron.psi.completions.lang.java;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;

public class CodeInsightUtil {


    @NotNull
    public static List<PsiType> getExpectedTypeArgs(PsiElement context,
                                                    PsiTypeParameterListOwner paramOwner,
                                                    Iterable<? extends PsiTypeParameter> typeParams, PsiClassType expectedType) {
        if (paramOwner instanceof PsiClass) {
            return GenericsUtil.getExpectedTypeArguments(context, (PsiClass)paramOwner, typeParams, expectedType);
        }

        PsiSubstitutor substitutor = SmartCompletionDecorator.calculateMethodReturnTypeSubstitutor((PsiMethod)paramOwner, expectedType);
        return ContainerUtil.map(typeParams, substitutor::substitute);
    }
}
