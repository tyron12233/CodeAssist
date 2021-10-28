package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;
import com.tyron.psi.patterns.PsiNamePatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.JavaResolveResult;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiParenthesizedExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

/**
 * @author peter
 */
public class PsiExpressionPattern<T extends PsiExpression, Self extends PsiExpressionPattern<T,Self>> extends PsiJavaElementPattern<T,Self> {
    protected PsiExpressionPattern(final Class<T> aClass) {
        super(aClass);
    }

    public Self ofType(@NotNull final ElementPattern pattern) {
        return with(new PatternCondition<T>("ofType") {
            @Override
            public boolean accepts(@NotNull final T t, final ProcessingContext context) {
                return pattern.accepts(t.getType(), context);
            }
        });
    }

    public PsiMethodCallPattern methodCall(final ElementPattern<? extends PsiMethod> method) {
        final PsiNamePatternCondition nameCondition = ContainerUtil.findInstance(method.getCondition().getConditions(), PsiNamePatternCondition.class);
        return new PsiMethodCallPattern().and(this).with(new PatternCondition<PsiMethodCallExpression>("methodCall") {
            @Override
            public boolean accepts(@NotNull PsiMethodCallExpression callExpression, ProcessingContext context) {
                PsiReferenceExpression methodExpression = callExpression.getMethodExpression();
                if (nameCondition != null && !nameCondition.getNamePattern().accepts(methodExpression.getReferenceName())) {
                    return false;
                }

                for (JavaResolveResult result : methodExpression.multiResolve(true)) {
                    if (method.accepts(result.getElement(), context)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public Self skipParentheses(final ElementPattern<? extends PsiExpression> expressionPattern) {
        return with(new PatternCondition<T>("skipParentheses") {
            @Override
            public boolean accepts(@NotNull T t, ProcessingContext context) {
                PsiExpression expression = t;
                while (expression instanceof PsiParenthesizedExpression) {
                    expression = ((PsiParenthesizedExpression)expression).getExpression();
                }
                return expressionPattern.accepts(expression, context);
            }
        });
    }

    public static class Capture<T extends PsiExpression> extends PsiExpressionPattern<T, Capture<T>> {
        public Capture(final Class<T> aClass) {
            super(aClass);
        }

    }
}