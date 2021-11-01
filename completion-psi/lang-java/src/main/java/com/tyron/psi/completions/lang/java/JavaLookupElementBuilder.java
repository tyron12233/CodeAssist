package com.tyron.psi.completions.lang.java;

import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Iconable;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiFormatUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiFormatUtilBase;

public class JavaLookupElementBuilder {

    private JavaLookupElementBuilder() {

    }

    public static LookupElementBuilder forMethod(@NotNull PsiMethod method, final PsiSubstitutor substitutor) {
        return forMethod(method, method.getName(), substitutor, null);
    }

    public static LookupElementBuilder forMethod(@NotNull PsiMethod method,
                                                 @NotNull String lookupString, final @NotNull PsiSubstitutor substitutor,
                                                 @Nullable PsiClass qualifierClass) {
        LookupElementBuilder builder = LookupElementBuilder.create(method, lookupString)
                .withPresentableText(method.getName())
                .withTailText(PsiFormatUtil.formatMethod(method, substitutor,
                        PsiFormatUtilBase.SHOW_PARAMETERS,
                        PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_TYPE));
        final PsiType returnType = method.getReturnType();
        if (returnType != null) {
            builder = builder.withTypeText(substitutor.substitute(returnType).getPresentableText());
        }
        builder = setBoldIfInClass(method, qualifierClass, builder);
        return builder;
    }

    private static LookupElementBuilder setBoldIfInClass(@NotNull PsiMember member, @Nullable PsiClass psiClass, @NotNull LookupElementBuilder builder) {
        if (psiClass != null && member.getManager().areElementsEquivalent(member.getContainingClass(), psiClass)) {
            return builder.bold();
        }
        return builder;
    }

}
