package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.InitialPatternCondition;
import com.tyron.psi.patterns.PatternCondition;
import com.tyron.psi.patterns.PsiElementPattern;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.codeInsight.AnnotationUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierList;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

/**
 * @author peter
 */
public class PsiModifierListOwnerPattern<T extends PsiModifierListOwner, Self extends PsiModifierListOwnerPattern<T,Self>> extends PsiElementPattern<T,Self> {
    public PsiModifierListOwnerPattern(@NotNull final InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected PsiModifierListOwnerPattern(final Class<T> aClass) {
        super(aClass);
    }

    public Self withModifiers(final String... modifiers) {
        return with(new PatternCondition<T>("withModifiers") {
            @Override
            public boolean accepts(@NotNull final T t, final ProcessingContext context) {
                return ContainerUtil.and(modifiers, s -> t.hasModifierProperty(s));
            }
        });
    }

    public Self withoutModifiers(final String... modifiers) {
        return with(new PatternCondition<T>("withoutModifiers") {
            @Override
            public boolean accepts(@NotNull final T t, final ProcessingContext context) {
                return ContainerUtil.and(modifiers, s -> !t.hasModifierProperty(s));
            }
        });
    }

    public Self withAnnotation(@NonNls final String qualifiedName) {
        return with(new PatternCondition<T>("withAnnotation") {
            @Override
            public boolean accepts(@NotNull final T t, final ProcessingContext context) {
                final PsiModifierList modifierList = t.getModifierList();
                return modifierList != null && modifierList.hasAnnotation(qualifiedName);
            }
        });
    }

    public Self withAnnotations(@NonNls final String... qualifiedNames) {
        return with(new PatternCondition<T>("withAnnotations") {
            @Override
            public boolean accepts(@NotNull final T t, final ProcessingContext context) {
                return AnnotationUtil.findAnnotation(t, qualifiedNames) != null;
            }
        });
    }

    public static class Capture<T extends PsiModifierListOwner> extends PsiModifierListOwnerPattern<T, Capture<T>> {
        public Capture(@NotNull InitialPatternCondition<T> condition) {
            super(condition);
        }
    }
}