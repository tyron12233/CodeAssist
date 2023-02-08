package com.tyron.completion.impl;

import com.tyron.completion.CompletionContributor;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProcess;
import com.tyron.completion.CompletionResult;
import com.tyron.completion.CompletionResultSet;
import com.tyron.completion.CompletionSorter;
import com.tyron.completion.PrefixMatcher;
import com.tyron.completion.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.util.Consumer;

public class CompletionServiceImpl extends BaseCompletionService {

    private static class CompletionResultSetImpl extends BaseCompletionResultSet {
        CompletionResultSetImpl(Consumer<? super CompletionResult> consumer, PrefixMatcher prefixMatcher,
                                CompletionContributor contributor, CompletionParameters parameters,
                                @Nullable CompletionSorter sorter, @Nullable CompletionResultSetImpl original) {
            super(consumer, prefixMatcher, contributor, parameters, sorter, original);
        }

        @Override
        public void addAllElements(@NotNull Iterable<? extends LookupElement> elements) {
//            CompletionThreadingBase.withBatchUpdate(() -> super.addAllElements(elements), myParameters.getProcess());
            super.addAllElements(elements);
        }

        @Override
        public void passResult(@NotNull CompletionResult result) {
            LookupElement element = result.getLookupElement();
            if (element != null && element.getUserData(LOOKUP_ELEMENT_CONTRIBUTOR) == null) {
                element.putUserData(LOOKUP_ELEMENT_CONTRIBUTOR, myContributor);
            }
            super.passResult(result);
        }

        @Override
        @NotNull
        public CompletionResultSet withPrefixMatcher(@NotNull final PrefixMatcher matcher) {
            if (matcher.equals(getPrefixMatcher())) {
                return this;
            }

            return new CompletionResultSetImpl(getConsumer(), matcher, myContributor, myParameters, mySorter, this);
        }

        @NotNull
        @Override
        public CompletionResultSet withRelevanceSorter(@NotNull CompletionSorter sorter) {
            return new CompletionResultSetImpl(getConsumer(), getPrefixMatcher(), myContributor, myParameters, sorter, this);
        }

        @Override
        public void addLookupAdvertisement(@NotNull String text) {
//            getCompletionService().setAdvertisementText(text);
        }

        @Override
        public void restartCompletionOnPrefixChange(ElementPattern<String> prefixCondition) {
            CompletionProcess process = myParameters.getProcess();
            if (process instanceof CompletionProcessBase) {
                ((CompletionProcessBase)process)
                        .addWatchedPrefix(myParameters.getOffset() - getPrefixMatcher().getPrefix().length(), prefixCondition);
            }
        }
        @Override
        public void restartCompletionWhenNothingMatches() {
            CompletionProcess process = myParameters.getProcess();
//            if (process instanceof CompletionProgressIndicator) {
//                ((CompletionProgressIndicator)process).getLookup().setStartCompletionWhenNothingMatches(true);
//            }
        }
    }
}
