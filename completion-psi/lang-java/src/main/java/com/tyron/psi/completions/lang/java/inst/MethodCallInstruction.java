package com.tyron.psi.completions.lang.java.inst;

import org.jetbrains.kotlin.com.intellij.psi.*;

public class MethodCallInstruction {


    public static boolean isVarArgCall(PsiMethod method, PsiSubstitutor substitutor, PsiExpression[] args, PsiParameter[] parameters) {
        if (!method.isVarArgs()) {
            return false;
        }

        int argCount = args.length;
        int paramCount = parameters.length;
        if (argCount > paramCount || argCount == paramCount - 1) {
            return true;
        }

        if (paramCount > 0 && argCount == paramCount) {
            PsiType lastArgType = args[argCount - 1].getType();
            return lastArgType != null && !substitutor.substitute(parameters[paramCount - 1].getType()).isAssignableFrom(lastArgType);
        }
        return false;
    }
}
