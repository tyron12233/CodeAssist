package com.tyron.completion.psi.codeInsight;

import static org.jetbrains.kotlin.com.intellij.util.ObjectUtils.tryCast;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.JavaResolveResult;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCall;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;

public class MethodCallUtils {
    /**
     * Returns a method/constructor parameter which corresponds to given argument
     * @param argument an argument to find the corresponding parameter
     * @return a parameter or null if supplied expression is not a call argument, call is not resolved or expression is a var-arg
     * argument.
     */
    @Nullable
    public static PsiParameter getParameterForArgument(@NotNull PsiExpression argument) {
        PsiElement argumentParent = argument.getParent();
        if (argumentParent instanceof PsiReferenceExpression) {
            PsiMethodCallExpression
                    callForQualifier = tryCast(argumentParent.getParent(), PsiMethodCallExpression.class);
            if (callForQualifier != null) {
                PsiMethod method = callForQualifier.resolveMethod();
//                if (method instanceof PsiExtensionMethod) {
//                    return ((PsiExtensionMethod)method).getTargetReceiverParameter();
//                }
            }
        }
        PsiExpressionList argList = tryCast(argumentParent, PsiExpressionList.class);
        if (argList == null) return null;
        PsiElement parent = argList.getParent();
        if (parent instanceof PsiAnonymousClass) {
            parent = parent.getParent();
        }
        PsiCall call = tryCast(parent, PsiCall.class);
        if (call == null) return null;
        PsiExpression[] args = argList.getExpressions();
        int index = ArrayUtil.indexOf(args, argument);
        if (index == -1) return null;
        PsiMethod method = call.resolveMethod();
        if (method == null) return null;
        PsiParameterList list = method.getParameterList();
        int count = list.getParametersCount();
        if (index >= count) return null;
        if (isVarArgCall(call) && index >= count - 1) return null;
//        return method instanceof PsiExtensionMethod ? ((PsiExtensionMethod)method).getTargetParameter(index) : list.getParameter(index);
        return list.getParameter(index);
    }

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
        return argumentList != null;
//        &&
//               MethodCallInstruction
//                       .isVarArgCall(method, substitutor, argumentList.getExpressions(), method.getParameterList().getParameters());
    }
}
