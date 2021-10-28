package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.InitialPatternCondition;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public class PsiFieldPattern extends PsiMemberPattern<PsiField, PsiFieldPattern>{
    public PsiFieldPattern() {
        super(new InitialPatternCondition<PsiField>(PsiField.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                return o instanceof PsiField;
            }
        });
    }
}