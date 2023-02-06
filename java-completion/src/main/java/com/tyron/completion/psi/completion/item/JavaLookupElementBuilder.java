package com.tyron.completion.psi.completion.item;

import androidx.annotation.Nullable;

import com.tyron.completion.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Iconable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiFormatUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiFormatUtilBase;

public final class JavaLookupElementBuilder {
  private JavaLookupElementBuilder() {
  }

  public static LookupElementBuilder forField(@NotNull PsiField field) {
    return forField(field, field.getName(), null);
  }

  public static LookupElementBuilder forField(@NotNull PsiField field,
                                              final String lookupString,
                                              final @Nullable PsiClass qualifierClass) {
    final LookupElementBuilder builder = LookupElementBuilder.create(field, lookupString);
//            .withIcon(
//            field.getIcon(Iconable.ICON_FLAG_VISIBILITY)
//            );
    return setBoldIfInClass(field, qualifierClass, builder);
  }

  public static LookupElementBuilder forMethod(@NotNull PsiMethod method, final PsiSubstitutor substitutor) {
    return forMethod(method, method.getName(), substitutor, null);
  }

  public static LookupElementBuilder forMethod(@NotNull PsiMethod method,
                                               @NotNull String lookupString, final @NotNull PsiSubstitutor substitutor,
                                               @Nullable PsiClass qualifierClass) {
    LookupElementBuilder builder = LookupElementBuilder.create(method, lookupString)
//            .withIcon(method.getIcon(Iconable.ICON_FLAG_VISIBILITY))
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

  public static LookupElementBuilder forClass(@NotNull PsiClass psiClass) {
    return forClass(psiClass, psiClass.getName());
  }

  public static LookupElementBuilder forClass(@NotNull PsiClass psiClass,
                                              final String lookupString) {
    return forClass(psiClass, lookupString, false);
  }

  public static LookupElementBuilder forClass(@NotNull PsiClass psiClass,
                                              final String lookupString,
                                              final boolean withLocation) {
    LookupElementBuilder builder =
            LookupElementBuilder.create(psiClass, lookupString);
    String name = psiClass.getName();
    if (StringUtil.isNotEmpty(name)) {
      builder = builder.withLookupString(name);
    }
    if (withLocation) {
      return builder.withTailText(" (" + PsiFormatUtil.getPackageDisplayName(psiClass) + ")", true);
    }
    return builder;
  }
}