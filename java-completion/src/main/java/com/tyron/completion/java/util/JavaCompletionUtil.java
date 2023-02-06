package com.tyron.completion.java.util;

import androidx.annotation.NonNull;

import com.tyron.completion.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiPolyVariantReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.ResolveResult;
import org.jetbrains.kotlin.com.intellij.psi.ResolveState;
import org.jetbrains.kotlin.com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.kotlin.com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.light.LightVariableBuilder;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;

public class JavaCompletionUtil {
    @NonNull
    public static PsiReferenceExpression createReference(@NonNull String text, @NonNull PsiElement context) {
        return (PsiReferenceExpression) JavaPsiFacade
                .getElementFactory(context.getProject()).createExpressionFromText(text, context);
    }

    public static FakePsiElement createContextWithXxxVariable(@NonNull PsiElement place, @NonNull PsiType varType) {
        return new FakePsiElement() {
            @Override
            public boolean processDeclarations(@NonNull PsiScopeProcessor processor,
                                               @NonNull ResolveState state,
                                               PsiElement lastParent,
                                               @NonNull PsiElement place) {
                return processor.execute(new LightVariableBuilder<>("xxx", varType, place), ResolveState.initial());
            }

            @Override
            public PsiElement getParent() {
                return place;
            }
        };
    }

    public static PsiElement resolveReference(PsiReference psiReference) {
        if (psiReference instanceof PsiPolyVariantReference) {
            ResolveResult[] results = ((PsiPolyVariantReference)psiReference).multiResolve(true);
            if (results.length == 1) return results[0].getElement();
        }
        return psiReference.resolve();
    }

    private static final Key<List<SmartPsiElementPointer<PsiMethod>>> ALL_METHODS_ATTRIBUTE = Key.create("allMethods");

    public static List<PsiMethod> getAllMethods(@NotNull LookupElement item) {
        List<SmartPsiElementPointer<PsiMethod>> pointers = item.getUserData(ALL_METHODS_ATTRIBUTE);
        if (pointers == null) return null;

        return ContainerUtil.mapNotNull(pointers, SmartPsiElementPointer::getElement);
    }

    public static boolean inSomePackage(@NotNull PsiElement context) {
        PsiFile contextFile = context.getContainingFile();
        return contextFile instanceof PsiClassOwner && StringUtil.isNotEmpty(((PsiClassOwner)contextFile).getPackageName());
    }

    public static boolean isSourceLevelAccessible(@NotNull PsiElement context,
                                           @NotNull PsiClass psiClass,
                                           boolean pkgContext) {
        return isSourceLevelAccessible(context, psiClass, pkgContext, psiClass.getContainingClass());
    }

    public static boolean isSourceLevelAccessible(PsiElement context,
                                                  @NotNull PsiClass psiClass,
                                                  boolean pkgContext,
                                                  @Nullable PsiClass qualifierClass) {
        if (!JavaPsiFacade.getInstance(psiClass.getProject()).getResolveHelper().isAccessible(psiClass, context, qualifierClass)) {
            return false;
        }

        if (pkgContext) {
            PsiClass topLevel = PsiUtil.getTopLevelClass(psiClass);
            if (topLevel != null) {
                String fqName = topLevel.getQualifiedName();
                return fqName == null || !StringUtil.isEmpty(StringUtil.getPackageName(fqName));
            }
        }

        return true;
    }
}
