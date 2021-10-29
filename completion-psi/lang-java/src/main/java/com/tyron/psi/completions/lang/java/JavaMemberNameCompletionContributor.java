package com.tyron.psi.completions.lang.java;

import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiElement;

import com.tyron.psi.patterns.ElementPattern;

import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

public class JavaMemberNameCompletionContributor {

    public static final ElementPattern<PsiElement> INSIDE_TYPE_PARAMS_PATTERN = psiElement().
            afterLeaf(psiElement().withText("?").andOr(
                    psiElement().afterLeaf("<", ","),
                    psiElement().afterSiblingSkipping(psiElement().whitespaceCommentEmptyOrError(), psiElement(PsiAnnotation.class))));

    static final int MAX_SCOPE_SIZE_TO_SEARCH_UNRESOLVED = 50000;
}
