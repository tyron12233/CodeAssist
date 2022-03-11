package com.tyron.completion.java.provider;

import androidx.annotation.NonNull;

import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.java.util.JavaCompletionUtil;
import com.tyron.completion.model.CompletionList;

import org.jetbrains.kotlin.com.intellij.psi.LambdaUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaParameterType;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.SymbolCollectingProcessor;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.MostlySingularMultiMap;

/**
 * Computes completion candidates using PSI from the kotlin compiler.
 */
public class JavaKotlincCompletionProvider {

    public void fillCompletionVariants(@NonNull PsiElement elementAt,
                                       @NonNull CompletionList.Builder builder) {
        PsiElement parent = elementAt.getParent();

        if (elementAt instanceof PsiIdentifier) {
            addIdentifierVariants(elementAt, builder);

            if (parent instanceof PsiJavaCodeReferenceElement) {
                PsiJavaCodeReferenceElement parentRef = (PsiJavaCodeReferenceElement) parent;
                completeReference(elementAt, parentRef, builder);
            }
        }
    }

    private void completeReference(PsiElement position,
                                   PsiJavaCodeReferenceElement parentRef,
                                   CompletionList.Builder builder) {
        PsiElement elementParent = position.getContext();
        if (elementParent instanceof PsiReferenceExpression) {
            final PsiExpression qualifierExpression =
                    ((PsiReferenceExpression) elementParent).getQualifierExpression();
            if (qualifierExpression instanceof PsiReferenceExpression) {
                final PsiElement resolve = ((PsiReferenceExpression) qualifierExpression).resolve();
                if (resolve instanceof PsiParameter) {
                    final PsiElement declarationScope = ((PsiParameter)resolve).getDeclarationScope();
                    if (((PsiParameter)resolve).getType() instanceof PsiLambdaParameterType) {
                        final PsiLambdaExpression lambdaExpression = (PsiLambdaExpression) declarationScope;
                        if (PsiTypesUtil.getExpectedTypeByParent(lambdaExpression) == null) {
                            final int parameterIndex = lambdaExpression.getParameterList().getParameterIndex((PsiParameter)resolve);
                            final boolean overloadsFound = LambdaUtil.processParentOverloads(lambdaExpression,  functionalInterfaceType -> {
                                PsiType qualifierType = LambdaUtil.getLambdaParameterFromType(functionalInterfaceType, parameterIndex);
                                if (qualifierType instanceof PsiWildcardType) {
                                    qualifierType = ((PsiWildcardType)qualifierType).getBound();
                                }
                                if (qualifierType == null) return;

                                PsiReferenceExpression fakeRef = JavaCompletionUtil
                                        .createReference("xxx.xxx", JavaCompletionUtil
                                                .createContextWithXxxVariable(position, qualifierType));
                                System.out.println(fakeRef);
                            });

                            if (overloadsFound) {
                                // TODO: 3/11/2022  
                            }
                        }
                    }
                }
            }
        }

        SymbolCollectingProcessor processor = new SymbolCollectingProcessor();
        parentRef.processVariants(processor);
        MostlySingularMultiMap<String, SymbolCollectingProcessor.ResultWithContext> results =
                processor.getResults();
        results.processAllValues(result -> {
            String name = result.getElement().getName();
            // TODO: customize completion item based on its PSI type
            builder.addItem(CompletionItemFactory.item(name));
            // continue processing all results
            // TODO: Stop when MAX_COMPLETION_LIST is reached
            return true;
        });
    }

    private void addIdentifierVariants(PsiElement elementAt, CompletionList.Builder builder) {

    }
}
