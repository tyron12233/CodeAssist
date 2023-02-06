package com.tyron.completion.java.provider;

import static org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns.elementType;
import static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns.psiElement;

import androidx.annotation.NonNull;

import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.java.util.JavaCompletionUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionItemWithMatchLevel;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.psi.completion.JavaKeywordCompletion;
import com.tyron.completion.psi.scope.CompletionElement;
import com.tyron.completion.psi.scope.JavaCompletionProcessor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.LambdaUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaParameterType;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.SymbolCollectingProcessor;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.MostlySingularMultiMap;

import java.util.Set;

/**
 * Computes completion candidates using PSI from the kotlin compiler.
 */
public class JavaKotlincCompletionProvider {

    private static final ElementPattern<PsiElement> AFTER_NUMBER_LITERAL =
            psiElement().afterLeaf(psiElement().withElementType(
                    elementType().oneOf(JavaTokenType.DOUBLE_LITERAL, JavaTokenType.LONG_LITERAL, JavaTokenType.FLOAT_LITERAL, JavaTokenType.INTEGER_LITERAL)));
    private static final ElementPattern<PsiElement> IMPORT_REFERENCE =
            psiElement().withParent(psiElement(PsiJavaCodeReferenceElement.class).withParent(
                    PsiImportStatementBase.class));
    private static final ElementPattern<PsiElement> CATCH_OR_FINALLY = psiElement().afterLeaf(
            psiElement().withText("}").withParent(
                    psiElement(PsiCodeBlock.class).afterLeaf(psiElement().withText(PsiKeyword.TRY))));
    private static final ElementPattern<PsiElement> INSIDE_CONSTRUCTOR = psiElement().inside(psiElement(
            PsiMethod.class).with(new PatternCondition<PsiMethod>("constructor") {
        @Override
        public boolean accepts(@NotNull PsiMethod psiMethod, ProcessingContext processingContext) {
            return psiMethod.isConstructor();
        }
    }));
//    private static final ElementPattern<PsiElement> AFTER_ENUM_CONSTANT =
//            psiElement().inside(psiElement(PsiTypeElement.class)).afterLeaf(
//                    psiElement().inside(true, psiElement(PsiEnumConstant.class), psiElement(PsiClass.class, PsiExpressionList.class)));
    public JavaKotlincCompletionProvider() {
    }

    public void fillCompletionVariants(@NonNull PsiElement position,
                                       @NonNull CompletionList.Builder builder) {
        if (!isInJavaContext(position)) {
            return;
        }

        if (AFTER_NUMBER_LITERAL.accepts(position) ) { //||
//            UNEXPECTED_REFERENCE_AFTER_DOT.accepts(position) ||
//            AFTER_ENUM_CONSTANT.accepts(position)) {
//            _result.stopHere();
            return;
        }

        PsiElement parent = position.getParent();

        if (position instanceof PsiIdentifier) {
            new JavaKeywordCompletion(position, builder);

            addIdentifierVariants(position, builder);

            addClassNames(position, builder);

            if (parent instanceof PsiJavaCodeReferenceElement) {
                PsiJavaCodeReferenceElement parentRef = (PsiJavaCodeReferenceElement) parent;
                completeReference(position, parentRef, builder);
            }
        }
    }

    private void addClassNames(PsiElement elementAt, CompletionList.Builder builder) {

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

        JavaCompletionProcessor.Options options = JavaCompletionProcessor.Options.DEFAULT_OPTIONS;
        JavaCompletionProcessor processor = new JavaCompletionProcessor(position, new ElementFilter() {
            @Override
            public boolean isAcceptable(Object o, @Nullable PsiElement psiElement) {
                return true;
            }

            @Override
            public boolean isClassAcceptable(Class aClass) {
                return true;
            }
        }, options, Condition.TRUE);
        parentRef.processVariants(processor);
        Iterable<CompletionElement> results = processor.getResults();
        results.forEach(result -> {
            CompletionItemWithMatchLevel item = CompletionItemFactory.forPsiElement(
                    (PsiNamedElement) result.getElement(), position);
            builder.addItem(item);
        });
    }

    private void addIdentifierVariants(PsiElement elementAt, CompletionList.Builder builder) {

    }

    public static boolean isInJavaContext(PsiElement position) {
        return PsiUtilCore.findLanguageFromElement(position).isKindOf(JavaLanguage.INSTANCE);
    }
}
