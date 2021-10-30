package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.lookup.AutoCompletionPolicy;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbAware;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

import java.util.ArrayList;
import java.util.List;

public class JavaNoVariantsDelegator extends CompletionContributor implements DumbAware {

    @Override
    public void fillCompletionVariants(CompletionParameters parameters, CompletionResultSet result) {

    }

    static void suggestNonImportedClasses(CompletionParameters parameters, CompletionResultSet result, @Nullable JavaCompletionSession session) {
        List<LookupElement> sameNamedBatch = new ArrayList<>();
        JavaClassNameCompletionContributor.addAllClasses(parameters, parameters.getInvocationCount() <= 2, result.getPrefixMatcher(), element -> {
            if (session != null && session.alreadyProcessed(element)) {
                return;
            }
            JavaPsiClassReferenceElement classElement = element.as(JavaPsiClassReferenceElement.CLASS_CONDITION_KEY);
            PsiElement position = parameters.getPosition();
            if (classElement != null && parameters.getInvocationCount() < 2) {
                if (JavaClassNameCompletionContributor.AFTER_NEW.accepts(position) &&
                        JavaPsiClassReferenceElement.isInaccessibleConstructorSuggestion(position, classElement.getObject())) {
                    return;
                }
                classElement.setAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE);
            }

        //    element = JavaCompletionUtil.highlightIfNeeded(null, element, element.getObject(), position);
            if (!sameNamedBatch.isEmpty() && !element.getLookupString().equals(sameNamedBatch.get(0).getLookupString())) {
                result.addAllElements(sameNamedBatch);
                sameNamedBatch.clear();
            }
            sameNamedBatch.add(element);
        });
        result.addAllElements(sameNamedBatch);
    }


}
