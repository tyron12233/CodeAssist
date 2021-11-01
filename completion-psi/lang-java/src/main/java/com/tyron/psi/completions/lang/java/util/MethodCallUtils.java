package com.tyron.psi.completions.lang.java.util;

import static org.jetbrains.kotlin.com.intellij.util.ObjectUtils.tryCast;

import com.tyron.psi.completions.lang.java.inst.MethodCallInstruction;

import org.jetbrains.annotations.Contract;
import org.jetbrains.kotlin.com.intellij.psi.JavaResolveResult;
import org.jetbrains.kotlin.com.intellij.psi.PsiCall;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;

public class MethodCallUtils {
    /**
     * Returns true if given method call is a var-arg call
     *
     * @param call a call to test
     * @return true if call is resolved to the var-arg method and var-arg form is actually used
     */
    @Contract(pure = true)
    public static boolean isVarArgCall(PsiCall call) {
        JavaResolveResult result = call.resolveMethodGenerics();
        PsiMethod method = tryCast(result.getElement(), PsiMethod.class);
        if(method == null || !method.isVarArgs()) return false;
        PsiSubstitutor substitutor = result.getSubstitutor();
        PsiExpressionList argumentList = call.getArgumentList();
        return argumentList != null &&
                MethodCallInstruction
                        .isVarArgCall(method, substitutor, argumentList.getExpressions(), method.getParameterList().getParameters());
    }
}
