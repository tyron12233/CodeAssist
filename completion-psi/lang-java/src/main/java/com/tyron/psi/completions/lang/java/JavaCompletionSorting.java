package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.CompletionType;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JavaCompletionSorting {

    private JavaCompletionSorting() {
    }

    public static CompletionResultSet addJavaSorting(final CompletionParameters parameters, CompletionResultSet result) {
        return result;
    }
}
