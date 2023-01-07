package com.tyron.completion.psi.completion;

import static org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns.psiElement;
import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.or;

import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassObjectAccessExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiSuperExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression;

public class JavaSmartCompletionContributor {

    static final ElementPattern<PsiElement> AFTER_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW));
    static final ElementPattern<PsiElement>
            AFTER_THROW_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW).afterLeaf(
            psiElement().withText(PsiKeyword.THROW)));

    public static final ElementPattern<PsiElement> INSIDE_EXPRESSION = or(
            psiElement().withParent(PsiExpression.class)
                    .andNot(psiElement().withParent(PsiLiteralExpression.class))
                    .andNot(psiElement().withParent(PsiMethodReferenceExpression.class)),
            psiElement().inside(psiElement(PsiClassObjectAccessExpression.class)),
            psiElement().inside(psiElement(PsiThisExpression.class)),
            psiElement().inside(psiElement(PsiSuperExpression.class)));
}
