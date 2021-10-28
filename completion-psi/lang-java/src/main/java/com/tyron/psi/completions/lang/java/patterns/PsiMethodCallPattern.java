package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class PsiMethodCallPattern extends PsiExpressionPattern<PsiMethodCallExpression, PsiMethodCallPattern> {
    PsiMethodCallPattern() {
        super(PsiMethodCallExpression.class);
    }

    public PsiMethodCallPattern withArguments(final ElementPattern<? extends PsiExpression>... arguments) {
        return with(new PatternCondition<PsiMethodCallExpression>("withArguments") {
            @Override
            public boolean accepts(@NotNull PsiMethodCallExpression callExpression, ProcessingContext context) {
                final PsiExpression[] actualArguments = callExpression.getArgumentList().getExpressions();
                if (arguments.length != actualArguments.length) {
                    return false;
                }
                for (int i = 0; i < actualArguments.length; i++) {
                    if (!arguments[i].accepts(actualArguments[i], context)) {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    public PsiMethodCallPattern withQualifier(final ElementPattern<? extends PsiExpression> qualifier) {
        return with(new PatternCondition<PsiMethodCallExpression>("withQualifier") {
            @Override
            public boolean accepts(@NotNull PsiMethodCallExpression psiMethodCallExpression, ProcessingContext context) {
                return qualifier.accepts(psiMethodCallExpression.getMethodExpression().getQualifierExpression(), context);
            }
        });
    }
}