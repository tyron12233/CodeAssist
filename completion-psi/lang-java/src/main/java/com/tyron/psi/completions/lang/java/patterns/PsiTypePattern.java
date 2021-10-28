package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.ObjectPattern;
import com.tyron.psi.patterns.PatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public class PsiTypePattern extends ObjectPattern<PsiType,PsiTypePattern> {
    protected PsiTypePattern() {
        super(PsiType.class);
    }

    public PsiTypePattern arrayOf(final ElementPattern pattern) {
        return with(new PatternCondition<PsiType>("arrayOf") {
            @Override
            public boolean accepts(@NotNull final PsiType psiType, final ProcessingContext context) {
                return psiType instanceof PsiArrayType &&
                        pattern.accepts(((PsiArrayType)psiType).getComponentType(), context);
            }
        });
    }

    public PsiTypePattern classType(final ElementPattern<? extends PsiClass> pattern) {
        return with(new PatternCondition<PsiType>("classType") {
            @Override
            public boolean accepts(@NotNull final PsiType psiType, final ProcessingContext context) {
                return psiType instanceof PsiClassType &&
                        pattern.accepts(((PsiClassType)psiType).resolve(), context);
            }
        });
    }
}