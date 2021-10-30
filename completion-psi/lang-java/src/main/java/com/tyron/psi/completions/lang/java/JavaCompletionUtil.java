package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionUtil;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.util.DocumentUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.RangeMarkerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiImmediateClassType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Map;
import java.util.Set;

public class JavaCompletionUtil {

    public static boolean inSomePackage(PsiElement context) {
        PsiFile contextFile = context.getContainingFile();
        return contextFile instanceof PsiClassOwner && StringUtil.isNotEmpty(((PsiClassOwner)contextFile).getPackageName());
    }

    @NotNull
    public static <T extends PsiType> T originalize(@NotNull T type) {
        if (!type.isValid()) {
            return type;
        }

        T result = new PsiTypeMapper() {
            private final Set<PsiClassType> myVisited = new ReferenceOpenHashSet<>();

            @Override
            public PsiType visitClassType(@NotNull final PsiClassType classType) {
                if (!myVisited.add(classType)) return classType;

                final PsiClassType.ClassResolveResult classResolveResult = classType.resolveGenerics();
                final PsiClass psiClass = classResolveResult.getElement();
                final PsiSubstitutor substitutor = classResolveResult.getSubstitutor();
                if (psiClass == null) return classType;

                return new PsiImmediateClassType(CompletionUtil.getOriginalOrSelf(psiClass), originalizeSubstitutor(substitutor));
            }

            private PsiSubstitutor originalizeSubstitutor(final PsiSubstitutor substitutor) {
                PsiSubstitutor originalSubstitutor = PsiSubstitutor.EMPTY;
                for (final Map.Entry<PsiTypeParameter, PsiType> entry : substitutor.getSubstitutionMap().entrySet()) {
                    final PsiType value = entry.getValue();
                    originalSubstitutor = originalSubstitutor.put(CompletionUtil.getOriginalOrSelf(entry.getKey()),
                            value == null ? null : mapType(value));
                }
                return originalSubstitutor;
            }


            @Override
            public PsiType visitType(@NotNull PsiType type) {
                return type;
            }
        }.mapType(type);
        if (result == null) {
            throw new AssertionError("Null result for type " + type + " of class " + type.getClass());
        }
        return result;
    }

    public static boolean isInExcludedPackage(@NotNull final PsiMember member, boolean allowInstanceInnerClasses) {
        final String name = PsiUtil.getMemberQualifiedName(member);
        if (name == null) return false;

        if (!member.hasModifierProperty(PsiModifier.STATIC)) {
            if (member instanceof PsiMethod || member instanceof PsiField) {
                return false;
            }
            if (allowInstanceInnerClasses && member instanceof PsiClass && member.getContainingClass() != null) {
                return false;
            }
        }

        return false;
//        return JavaProjectCodeInsightSettings.getSettings(member.getProject()).isExcluded(name);
    }

    @Nullable
    public static RangeMarker insertTemporary(int endOffset, Document document, String temporary) {
        final CharSequence chars = document.getCharsSequence();
        if (endOffset < chars.length() && Character.isJavaIdentifierPart(chars.charAt(endOffset))){
            DocumentUtils.insertString(document, endOffset, temporary);
//            RangeMarkerImpl impl = new RangeMarkerImpl();
//            RangeMarker toDelete = document.createRangeMarker(endOffset, endOffset + 1);
//            toDelete.setGreedyToLeft(true);
//            toDelete.setGreedyToRight(true);
//            return toDelete;
        }
        return null;
        //throw new UnsupportedOperationException("Not yet implemented, inserTemporary()");
    }

    @Nullable
    static PsiElement resolveReference(final PsiReference psiReference) {
        if (psiReference instanceof PsiPolyVariantReference) {
            final ResolveResult[] results = ((PsiPolyVariantReference)psiReference).multiResolve(true);
            if (results.length == 1) return results[0].getElement();
        }
        return psiReference.resolve();
    }

    public static int findQualifiedNameStart(@NotNull InsertionContext context) {
        int start = context.getTailOffset() - 1;
        while (start >= 0) {
            char ch = context.getDocument().getCharsSequence().charAt(start);
            if (!Character.isJavaIdentifierPart(ch) && ch != '.') break;
            start--;
        }
        return start + 1;
    }


    public static boolean isSourceLevelAccessible(@NotNull PsiElement context,
                                                  @NotNull PsiClass psiClass,
                                                  final boolean pkgContext) {
        return isSourceLevelAccessible(context, psiClass, pkgContext, psiClass.getContainingClass());
    }

    private static boolean isSourceLevelAccessible(PsiElement context,
                                                   @NotNull PsiClass psiClass,
                                                   final boolean pkgContext,
                                                   @Nullable PsiClass qualifierClass) {
        if (!JavaPsiFacade.getInstance(psiClass.getProject()).getResolveHelper().isAccessible(psiClass, context, qualifierClass)) {
            return false;
        }

        if (pkgContext) {
            PsiClass topLevel = PsiUtil.getTopLevelClass(psiClass);
            if (topLevel != null) {
                String fqName = topLevel.getQualifiedName();
                if (fqName != null && StringUtil.isEmpty(StringUtil.getPackageName(fqName))) {
                    return false;
                }
            }
        }

        return true;
    }
}
