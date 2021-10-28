package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class PsiClassNamePatternCondition extends PatternCondition<PsiClass> {

    private final ElementPattern<String> namePattern;

    public PsiClassNamePatternCondition(ElementPattern<String> pattern) {
        this("withQualifiedName", pattern);
    }

    public PsiClassNamePatternCondition(@Nullable String debugMethodName, ElementPattern<String> pattern) {
        super(debugMethodName);
        namePattern = pattern;
    }

    @Override
    public boolean accepts(@NotNull PsiClass aClass, ProcessingContext context) {
        return namePattern.accepts(aClass.getQualifiedName(), context);
    }

    public ElementPattern<String> getNamePattern() {
        return namePattern;
    }
}