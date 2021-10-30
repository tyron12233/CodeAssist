package com.tyron.psi.completions.lang.java;

import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiElement;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionResult;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.CompletionUtil;
import com.tyron.psi.lookup.AutoCompletionPolicy;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbAware;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.psi.PsiPrimitiveType;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.List;

public class JavaNoVariantsDelegator extends CompletionContributor implements DumbAware {

    @Override
    public void fillCompletionVariants(CompletionParameters parameters, CompletionResultSet result) {
        ResultTracker tracker = new ResultTracker(result) {
            @Override
            public void consume(CompletionResult plainResult) {
                super.consume(plainResult);

                LookupElement element = plainResult.getLookupElement();
                if (element instanceof TypeArgumentCompletionProvider.TypeArgsLookupElement) {
                    ((TypeArgumentCompletionProvider.TypeArgsLookupElement)element).registerSingleClass(session);
                }
            }
        };
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

    private static void addNullKeyword(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        if (JavaSmartCompletionContributor.INSIDE_EXPRESSION.accepts(parameters.getPosition()) &&
                !psiElement().afterLeaf(".").accepts(parameters.getPosition()) &&
                result.getPrefixMatcher().getPrefix().startsWith("n")) {
            ExpectedTypeInfo[] infos = JavaSmartCompletionContributor.getExpectedTypes(parameters);
            for (ExpectedTypeInfo info : infos) {
                if (!(info.getType() instanceof PsiPrimitiveType)) {
                    LookupElement item = BasicExpressionCompletionContributor.createKeywordLookupItem(parameters.getPosition(), PsiKeyword.NULL);
                    result.addElement(JavaSmartCompletionContributor.decorate(item, ContainerUtil.newHashSet(infos)));
                    return;
                }
            }
        }
    }

    public static class ResultTracker implements Consumer<CompletionResult> {
        private final CompletionResultSet myResult;
        public final JavaCompletionSession session;
        boolean hasStartMatches = false;
        public boolean containsOnlyPackages = true;
        public BetterPrefixMatcher betterMatcher;

        public ResultTracker(CompletionResultSet result) {
            myResult = result;
            betterMatcher = new BetterPrefixMatcher.AutoRestarting(result);
            session = new JavaCompletionSession(result);
        }

        @Override
        public void consume(CompletionResult plainResult) {
            myResult.passResult(plainResult);

            if (!hasStartMatches && plainResult.getPrefixMatcher().isStartMatch(plainResult.getLookupElement())) {
                hasStartMatches = true;
            }

            LookupElement element = plainResult.getLookupElement();
            if (containsOnlyPackages && !(CompletionUtil.getTargetElement(element) instanceof PsiPackage)) {
                containsOnlyPackages = false;
            }

            session.registerClassFrom(element);

            betterMatcher = betterMatcher.improve(plainResult);
        }
    }

}
