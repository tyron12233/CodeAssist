package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

/**
 * @author ven
 */
@FunctionalInterface
public interface TextOccurenceProcessor {
    boolean execute(@NotNull PsiElement element, int offsetInElement);
}
