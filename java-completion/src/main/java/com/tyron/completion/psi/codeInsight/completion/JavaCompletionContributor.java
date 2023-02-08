package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.CompletionContributor;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionResultSet;
import com.tyron.completion.java.provider.JavaKotlincCompletionProvider;

import org.jetbrains.annotations.NotNull;

public class JavaCompletionContributor extends CompletionContributor {

    public JavaCompletionContributor() {

    }

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters,
                                       @NotNull CompletionResultSet result) {
        // TODO: Move logic here
        new JavaKotlincCompletionProvider().fillCompletionVariants(
                parameters,
                result
        );
    }
}
