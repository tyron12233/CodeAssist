package com.tyron.psi.completions.lang.java;

import android.util.Log;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionProvider;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.CompletionType;
import com.tyron.psi.lookup.LookupElementBuilder;
import com.tyron.psi.patterns.PlatformPatterns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiUnaryExpression;
import org.jetbrains.kotlin.com.intellij.psi.ResolveState;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.scope.util.PsiScopesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.js.translate.utils.PsiUtils;

public class TestCompletionContributor extends CompletionContributor {

    public TestCompletionContributor() {

    }
    @Override
    public void fillCompletionVariants(CompletionParameters parameters, CompletionResultSet result) {
        PsiElement element = parameters.getPosition();
        PsiReference reference = element.getReference();
        while (reference == null) {
            element = element.getParent();
            if (element == null) {
                break;
            }
            reference = element.getReference();
        }
        if (reference instanceof PsiReferenceExpression) {
            PsiElement parent = ((PsiReferenceExpression) reference).getParent();
            Log.d("REFERENCE TYPE", " " + parent);
        }
    }

    private void addIdentifiers(PsiElement element, CompletionResultSet result) {
        PsiExpression context = PsiTreeUtil.getParentOfType(element, PsiExpression.class);
        if (context == null) {
            return;
        }
        PsiScopesUtil.treeWalkUp(new PsiScopeProcessor() {
            @Override
            public boolean execute(@NotNull PsiElement psiElement, @NotNull ResolveState resolveState) {
                result.consume(LookupElementBuilder.create(psiElement));
                return true;
            }
        }, context, context.getContainingFile());
    }
}
