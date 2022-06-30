package com.tyron.completion.java.patterns;

import static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns.psiElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiIfStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import java.util.Arrays;
import java.util.List;

public class PsiElementPatterns {

    public static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaElementPattern.@NotNull Capture<PsiElement> insideStarting(final ElementPattern<? extends PsiElement> ancestor) {
        return psiElement().with(new PatternCondition<PsiElement>("insideStarting") {
            @Override
            public boolean accepts(@NotNull PsiElement start, ProcessingContext context) {
                PsiElement element = start.getParent();
                TextRange range = start.getTextRange();
                if (range == null) return false;

                int startOffset = range.getStartOffset();
                while (element != null && element.getTextRange() != null && element.getTextRange().getStartOffset() == startOffset) {
                    if (ancestor.accepts(element, context)) {
                        return true;
                    }
                    element = element.getParent();
                }
                return false;
            }
        });
    }

    public static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaElementPattern.@NotNull Capture<PsiElement> withParents(Class<? extends PsiElement>... types) {
        return psiElement().with(new PatternCondition<PsiElement>("withParents") {
            @Override
            public boolean accepts(@NotNull PsiElement psiElement,
                                   ProcessingContext processingContext) {
                PsiElement parent = psiElement.getContext();
                for (Class<? extends PsiElement> type : types) {
                    if (parent == null || !type.isInstance(parent)) {
                        return false;
                    }
                    parent = parent.getContext();
                }
                return true;
            }
        });
    }
}
