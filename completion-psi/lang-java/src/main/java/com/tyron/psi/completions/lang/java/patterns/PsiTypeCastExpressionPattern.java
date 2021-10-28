package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class PsiTypeCastExpressionPattern extends PsiExpressionPattern<PsiTypeCastExpression, PsiTypeCastExpressionPattern> {
    PsiTypeCastExpressionPattern() {
        super(PsiTypeCastExpression.class);
    }

    public PsiTypeCastExpressionPattern withOperand(final ElementPattern<? extends PsiExpression> operand) {
        return with(new PatternCondition<PsiTypeCastExpression>("withOperand") {
            @Override
            public boolean accepts(@NotNull PsiTypeCastExpression psiTypeCastExpression, ProcessingContext context) {
                return operand.accepts(psiTypeCastExpression.getOperand(), context);
            }
        });
    }
}