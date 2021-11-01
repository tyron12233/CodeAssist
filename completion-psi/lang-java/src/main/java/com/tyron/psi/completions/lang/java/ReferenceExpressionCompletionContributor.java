package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionUtil;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.filter.types.AssignableFromFilter;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.filters.AndFilter;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.infos.CandidateInfo;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

import java.util.Collections;
import java.util.Set;

public class ReferenceExpressionCompletionContributor {

    static Set<LookupElement> completeFinalReference(PsiElement element,
                                                     PsiJavaCodeReferenceElement reference,
                                                     ElementFilter filter,
                                                     PsiType expectedType,
                                                     CompletionParameters parameters) {
        final Set<PsiField> used = parameters.getInvocationCount() < 2 ? findConstantsUsedInSwitch(element) : Collections.emptySet();

        final Set<LookupElement> elements =
                JavaSmartCompletionContributor.completeReference(element, reference, new AndFilter(filter, new ElementFilter() {
                    @Override
                    public boolean isAcceptable(Object o, PsiElement context) {
                        if (o instanceof CandidateInfo) {
                            final CandidateInfo info = (CandidateInfo)o;
                            final PsiElement member = info.getElement();

                            if (expectedType.equals(PsiType.VOID)) {
                                return member instanceof PsiMethod;
                            }

                            if (member instanceof PsiEnumConstant && used.contains(CompletionUtil.getOriginalOrSelf(member))) {
                                return false;
                            }

                            return AssignableFromFilter.isAcceptable(member, element, expectedType, info.getSubstitutor());
                        }
                        return false;
                    }

                    @Override
                    public boolean isClassAcceptable(Class hintClass) {
                        return true;
                    }
                }), false, true, parameters, PrefixMatcher.ALWAYS_TRUE);
        for (LookupElement lookupElement : elements) {
            if (lookupElement.getObject() instanceof PsiMethod) {
                final JavaMethodCallElement item = lookupElement.as(JavaMethodCallElement.CLASS_CONDITION_KEY);
                if (item != null) {
//                    item.setInferenceSubstitutorFromExpectedType(element, expectedType);
//                    checkTooGeneric(item);
                }
            }
        }

        return elements;
    }

    @NotNull
    public static Set<PsiField> findConstantsUsedInSwitch(@Nullable PsiElement position) {
        return Collections.emptySet();
//        return JavaCompletionContributor.IN_SWITCH_LABEL.accepts(position)
//                ? findConstantsUsedInSwitch(Objects.requireNonNull(PsiTreeUtil.getParentOfType(position, PsiSwitchBlock.class)))
//                : Collections.emptySet();
    }

}
