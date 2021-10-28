package com.tyron.psi.completion.impl;

import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.not;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionProcess;
import com.tyron.psi.completion.CompletionResult;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.CompletionService;
import com.tyron.psi.completion.CompletionSorter;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementWeigher;
import com.tyron.psi.patterns.CharPattern;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.Consumer;

public class CompletionServiceImpl extends CompletionService {

    private static final Logger LOG = Logger.getInstance(CompletionServiceImpl.class);

    @Nullable protected CompletionProcess myApiCompletionProcess;

    public static final Key<CompletionContributor> LOOKUP_ELEMENT_CONTRIBUTOR = Key.create("lookup element contributor");


    @Override
    public void performCompletion(CompletionParameters parameters, Consumer<? super CompletionResult> consumer) {
        myApiCompletionProcess = parameters.getProcess();
        try {
            super.performCompletion(parameters, consumer);
        } finally {
            myApiCompletionProcess = null;
        }
    }

    @Override
    public void setAdvertisementText(String text) {

    }

    @Override
    protected String suggestPrefix(CompletionParameters parameters) {
        final PsiElement position = parameters.getPosition();
        final int offset = parameters.getOffset();
        TextRange range = position.getTextRange();
        assert range.containsOffset(offset) : position + "; " + offset + " not in " + range;
        //noinspection deprecation
        return CompletionUtil.findPrefix(position, offset, not(CharPattern.javaIdentifierPartCharacter()));
    }

    @NonNull
    @Override
    protected PrefixMatcher createMatcher(String prefix, boolean typoTolerant) {
        return PrefixMatcher.ALWAYS_TRUE;
    }

    @Override
    public CompletionProcess getCurrentCompletion() {
        return myApiCompletionProcess;
    }

    @Override
    public CompletionSorter defaultSorter(CompletionParameters parameters, PrefixMatcher matcher) {
        return new CompletionSorter() {
            @Override
            public CompletionSorter weighBefore(@NonNull String beforeId, LookupElementWeigher... weighers) {
                return null;
            }

            @Override
            public CompletionSorter weighAfter(@NonNull String afterId, LookupElementWeigher... weighers) {
                return null;
            }

            @Override
            public CompletionSorter weigh(LookupElementWeigher weigher) {
                return null;
            }
        };
    }

    @Override
    public CompletionSorter emptySorter() {
        return CompletionSorter.emptySorter();
    }

    @Override
    protected CompletionResultSet createResultSet(CompletionParameters parameters, org.jetbrains.kotlin.com.intellij.util.Consumer<? super CompletionResult> consumer, CompletionContributor contributor, PrefixMatcher matcher) {
        return new CompletionResultSet(matcher, consumer, contributor) {
            @Override
            public void addElement(@NonNull LookupElement element) {

            }

            @NonNull
            @Override
            public CompletionResultSet withPrefixMatcher(@NonNull PrefixMatcher matcher) {
                return null;
            }

            @NonNull
            @Override
            public CompletionResultSet withPrefixMatcher(@NonNull String prefix) {
                return null;
            }

            @NonNull
            @Override
            public CompletionResultSet withRelevanceSorter(@NonNull CompletionSorter sorter) {
                return null;
            }

            @Override
            public void addLookupAdvertisement(@NonNull String text) {

            }

            @NonNull
            @Override
            public CompletionResultSet caseInsensitive() {
                return null;
            }

            @Override
            public void restartCompletionOnPrefixChange(ElementPattern<String> prefixCondition) {

            }

            @Override
            public void restartCompletionWhenNothingMatches() {

            }
        };
    }
}
