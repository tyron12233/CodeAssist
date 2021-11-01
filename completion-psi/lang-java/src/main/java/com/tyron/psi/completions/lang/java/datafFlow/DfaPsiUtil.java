package com.tyron.psi.completions.lang.java.datafFlow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.util.containers.JBIterable;

import java.util.Arrays;

public class DfaPsiUtil {

    /**
     * Try to restore type parameters based on the expression type
     *
     * @param expression expression which type is a supertype of the type to generify
     * @param type a type to generify
     * @return a generified type, or original type if generification is not possible
     */
    public static PsiType tryGenerify(PsiExpression expression, PsiType type) {
        if (!(type instanceof PsiClassType)) {
            return type;
        }
        PsiClassType classType = (PsiClassType)type;
        if (!classType.isRaw()) {
            return classType;
        }
        PsiClass psiClass = classType.resolve();
        if (psiClass == null) return classType;
        PsiType expressionType = expression.getType();
        if (!(expressionType instanceof PsiClassType)) return classType;
        PsiClassType result = GenericsUtil.getExpectedGenericType(expression, psiClass, (PsiClassType)expressionType);
        if (result.isRaw()) {
            PsiClass aClass = result.resolve();
            if (aClass != null) {
                int length = aClass.getTypeParameters().length;
                PsiWildcardType wildcard = PsiWildcardType.createUnbounded(aClass.getManager());
                PsiType[] arguments = new PsiType[length];
                Arrays.fill(arguments, wildcard);
                return JavaPsiFacade.getElementFactory(aClass.getProject()).createType(aClass, arguments);
            }
        }
        return result;
    }

    @Nullable
    public static PsiElement getTopmostBlockInSameClass(@NotNull PsiElement position) {
        return JBIterable.
                generate(position, PsiElement::getParent).
                takeWhile(e -> !(e instanceof PsiMember || e instanceof PsiFile || e instanceof PsiLambdaExpression)).
                filter(e -> e instanceof PsiCodeBlock || e instanceof PsiExpression && e.getParent() instanceof PsiLambdaExpression).
                last();
    }
}
