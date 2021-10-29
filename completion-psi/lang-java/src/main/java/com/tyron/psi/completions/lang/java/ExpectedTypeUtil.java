package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completions.lang.java.resolve.CompletionParameterTypeInferencePolicy;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.DefaultParameterTypeInferencePolicy;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;

public class ExpectedTypeUtil {
    @Nullable
    public static PsiSubstitutor inferSubstitutor(final PsiMethod method, final PsiMethodCallExpression callExpr, boolean forCompletion) {
        final PsiResolveHelper helper = JavaPsiFacade.getInstance(method.getProject()).getResolveHelper();
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        PsiExpression[] args = callExpr.getArgumentList().getExpressions();
        PsiSubstitutor result = PsiSubstitutor.EMPTY;
        for (PsiTypeParameter typeParameter : PsiUtil.typeParametersIterable(method.getContainingClass())) {
            PsiType type = helper.inferTypeForMethodTypeParameter(typeParameter, parameters, args, PsiSubstitutor.EMPTY, callExpr.getParent(),
                    forCompletion ? CompletionParameterTypeInferencePolicy.INSTANCE : DefaultParameterTypeInferencePolicy.INSTANCE);
            if (PsiType.NULL.equals(type)) return null;
            result = result.put(typeParameter, type);
        }

        return result;
    }
}
