package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionUtil;

import org.jetbrains.annotations.NotNull;
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
}
