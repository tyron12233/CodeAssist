package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiBinaryExpression;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public class PsiBinaryExpressionPattern extends PsiExpressionPattern<PsiBinaryExpression, PsiBinaryExpressionPattern>{
    protected PsiBinaryExpressionPattern() {
        super(PsiBinaryExpression.class);
    }

    public PsiBinaryExpressionPattern left(@NotNull final ElementPattern pattern) {
        return with(new PatternCondition<PsiBinaryExpression>("left") {
            @Override
            public boolean accepts(@NotNull final PsiBinaryExpression psiBinaryExpression, final ProcessingContext context) {
                return pattern.accepts(psiBinaryExpression.getLOperand(), context);
            }
        });
    }

    public PsiBinaryExpressionPattern right(@NotNull final ElementPattern pattern) {
        return with(new PatternCondition<PsiBinaryExpression>("right") {
            @Override
            public boolean accepts(@NotNull final PsiBinaryExpression psiBinaryExpression, final ProcessingContext context) {
                return pattern.accepts(psiBinaryExpression.getROperand(), context);
            }
        });
    }

    public PsiBinaryExpressionPattern operation(final ElementPattern pattern) {
        return with(new PatternCondition<PsiBinaryExpression>("operation") {
            @Override
            public boolean accepts(@NotNull final PsiBinaryExpression psiBinaryExpression, final ProcessingContext context) {
                return pattern.accepts(psiBinaryExpression.getOperationSign(), context);
            }
        });
    }

}