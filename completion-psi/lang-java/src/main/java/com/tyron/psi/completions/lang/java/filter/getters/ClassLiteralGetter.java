package com.tyron.psi.completions.lang.java.filter.getters;

import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.JavaSmartCompletionParameters;
import com.tyron.psi.lookup.AutoCompletionPolicy;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.CommonClassNames;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;

public final class ClassLiteralGetter {

    public static void addCompletions(@NotNull final JavaSmartCompletionParameters parameters,
                                      @NotNull Consumer<? super LookupElement> result, final PrefixMatcher matcher) {
        PsiType expectedType = parameters.getDefaultType();
        if (!InheritanceUtil.isInheritor(expectedType, CommonClassNames.JAVA_LANG_CLASS)) {
            expectedType = parameters.getExpectedType();
            if (!InheritanceUtil.isInheritor(expectedType, CommonClassNames.JAVA_LANG_CLASS)) {
                return;
            }
        }

        PsiType classParameter = PsiUtil.substituteTypeParameter(expectedType, CommonClassNames.JAVA_LANG_CLASS, 0, false);

        boolean addInheritors = false;
        PsiElement position = parameters.getPosition();
        if (classParameter instanceof PsiWildcardType) {
            final PsiWildcardType wildcardType = (PsiWildcardType)classParameter;
            classParameter = wildcardType.isSuper() ? wildcardType.getSuperBound() : wildcardType.getExtendsBound();
            addInheritors = wildcardType.isExtends() && classParameter instanceof PsiClassType;
        } else if (!matcher.getPrefix().isEmpty()) {
            addInheritors = true;
            classParameter = PsiType.getJavaLangObject(position.getManager(), position.getResolveScope());
        }
        if (classParameter != null) {
            PsiFile file = position.getContainingFile();
            addClassLiteralLookupElement(classParameter, result, file);
            if (addInheritors) {
                addInheritorClassLiterals(file, classParameter, result, matcher);
            }
        }
    }

    private static void addInheritorClassLiterals(final PsiFile context,
                                                  final PsiType classParameter,
                                                  final Consumer<? super LookupElement> result, PrefixMatcher matcher) {
        final String canonicalText = classParameter.getCanonicalText();
        if (CommonClassNames.JAVA_LANG_OBJECT.equals(canonicalText) && StringUtil.isEmpty(matcher.getPrefix())) {
            return;
        }

        //CodeInsightUtil.processSubTypes(classParameter, context, true, matcher, type -> addClassLiteralLookupElement(type, result, context));
    }

    private static void addClassLiteralLookupElement(@Nullable final PsiType type, final Consumer<? super LookupElement> resultSet, final PsiFile context) {
        if (type instanceof PsiClassType &&
                PsiUtil.resolveClassInType(type) != null &&
                !((PsiClassType)type).hasParameters() &&
                !(((PsiClassType)type).resolve() instanceof PsiTypeParameter)) {
            try {
                resultSet.consume(AutoCompletionPolicy.NEVER_AUTOCOMPLETE.applyPolicy(LookupElementBuilder.create(type.getPresentableText())));//new ClassLiteralLookupElement((PsiClassType)type, context)));
            }
            catch (IncorrectOperationException ignored) {
            }
        }
    }
}
