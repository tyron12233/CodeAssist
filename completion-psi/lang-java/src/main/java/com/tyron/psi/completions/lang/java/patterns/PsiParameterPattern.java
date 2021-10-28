package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternConditionPlus;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author Gregory Shrago
 */
public class PsiParameterPattern extends PsiModifierListOwnerPattern<PsiParameter, PsiParameterPattern> {

    protected PsiParameterPattern() {
        super(PsiParameter.class);
    }

    public PsiParameterPattern ofMethod(int index, ElementPattern pattern) {
        return with(new PatternConditionPlus<PsiParameter, PsiMethod>("ofMethod", pattern) {
            @Override
            public boolean processValues(PsiParameter t,
                                         ProcessingContext context,
                                         PairProcessor<? super PsiMethod, ? super ProcessingContext> processor) {
                PsiElement scope = t.getDeclarationScope();
                if (!(scope instanceof PsiMethod)) return true;
                return processor.process((PsiMethod)scope, context);
            }

            @Override
            public boolean accepts(@NotNull final PsiParameter t, final ProcessingContext context) {
                if (!super.accepts(t, context)) return false;
                PsiMethod psiMethod = (PsiMethod)t.getDeclarationScope();

                PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
                if (index < 0 || index >= parameters.length || !t.equals(parameters[index])) return false;
                return true;
            }
        });
    }

    public PsiParameterPattern ofMethod(ElementPattern<?> pattern) {
        return with(new PatternConditionPlus<PsiParameter, PsiMethod>("ofMethod", pattern) {
            @Override
            public boolean processValues(PsiParameter t,
                                         ProcessingContext context,
                                         PairProcessor<? super PsiMethod, ? super ProcessingContext> processor) {
                PsiElement scope = t.getDeclarationScope();
                if (!(scope instanceof PsiMethod)) return true;
                return processor.process((PsiMethod)scope, context);
            }
        });
    }
}
