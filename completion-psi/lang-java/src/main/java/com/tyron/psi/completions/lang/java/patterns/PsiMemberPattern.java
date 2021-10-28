package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.InitialPatternCondition;
import com.tyron.psi.patterns.PatternConditionPlus;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public class PsiMemberPattern<T extends PsiMember, Self extends PsiMemberPattern<T,Self>> extends PsiModifierListOwnerPattern<T,Self> {
    public PsiMemberPattern(@NotNull final InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected PsiMemberPattern(final Class<T> aClass) {
        super(aClass);
    }

    @NotNull
    public Self inClass(final @NonNls String qname) {
        return inClass(PsiJavaPatterns.psiClass().withQualifiedName(qname));
    }

    @NotNull
    public Self inClass(final ElementPattern pattern) {
        return with(new PatternConditionPlus<T, PsiClass>("inClass", pattern) {
            @Override
            public boolean processValues(T t, ProcessingContext context, PairProcessor<? super PsiClass, ? super ProcessingContext> processor) {
                return processor.process(t.getContainingClass(), context);
            }
        });
    }

    public static class Capture extends PsiMemberPattern<PsiMember, Capture> {

        protected Capture() {
            super(new InitialPatternCondition<PsiMember>(PsiMember.class) {
                @Override
                public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                    return o instanceof PsiMember;
                }
            });
        }
    }
}