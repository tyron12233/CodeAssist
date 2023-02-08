package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.CompletionContributor;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.CompletionResultSet;
import com.tyron.completion.CompletionType;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns;
import org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteral;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class JavaCompletionContributor extends CompletionContributor {

    public JavaCompletionContributor() {
        extend(CompletionType.SMART, PlatformPatterns.psiElement().withElementType(JavaTokenType.STRING_LITERAL), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet result) {
                result.addElement(LookupElementBuilder.create("Hello World").bold());
            }
        });
    }
}
