package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionParameters;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

/**
 * @author peter
 */
public class JavaSmartCompletionParameters {
    private final CompletionParameters myParameters;
    private final ExpectedTypeInfo myExpectedType;

    public JavaSmartCompletionParameters(CompletionParameters parameters, final ExpectedTypeInfo expectedType) {
        myParameters = parameters;
        myExpectedType = expectedType;
    }

    @NotNull public PsiType getExpectedType() {
        return myExpectedType.getType();
    }

    public PsiType getDefaultType() {
        return myExpectedType.getDefaultType();
    }

    public PsiElement getPosition() {
        return myParameters.getPosition();
    }

    public CompletionParameters getParameters() {
        return myParameters;
    }
}