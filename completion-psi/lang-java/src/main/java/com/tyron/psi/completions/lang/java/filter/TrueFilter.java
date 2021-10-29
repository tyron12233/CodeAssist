package com.tyron.psi.completions.lang.java.filter;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;

public final class TrueFilter implements ElementFilter {
    public static final ElementFilter INSTANCE = new TrueFilter();

    private TrueFilter() { }

    @Override
    public boolean isClassAcceptable(Class hintClass) {
        return true;
    }

    @Override
    public boolean isAcceptable(Object element, PsiElement context) {
        return true;
    }

    @Override
    public String toString() {
        return "true";
    }
}