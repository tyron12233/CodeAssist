package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionProvider;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.CompletionType;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbAware;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns;
import org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class JavaCompletionContributor extends CompletionContributor implements DumbAware {

    @Override
    public void fillCompletionVariants(CompletionParameters parameters, CompletionResultSet result) {
        extend(CompletionType.BASIC, new ElementPattern<PsiElement>() {
            @Override
            public boolean accepts(@Nullable Object o) {
                return true;
            }

            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext processingContext) {
                return true;
            }

            @Override
            public ElementPatternCondition<PsiElement> getCondition() {
                return null;
            }
        }, new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(CompletionParameters parameters, ProcessingContext context, CompletionResultSet result) {
                result.addElement(new LookupElement() {
                    @Override
                    public String getLookupString() {
                        return "HELLO";
                    }
                });
            }
        });
    }
}
