package com.tyron.psi.completion.impl;

import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.not;
import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionProcess;
import com.tyron.psi.completion.CompletionResult;
import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.CompletionService;
import com.tyron.psi.completion.CompletionSorter;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.CamelHumpMatcher;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementWeigher;
import com.tyron.psi.patterns.CharPattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.Consumer;

public class CompletionServiceImpl extends CompletionService {

    private static final Logger LOG = Logger.getInstance(CompletionServiceImpl.class);

    @Nullable
    protected CompletionProcess myApiCompletionProcess;

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

    @NotNull
    @Override
    protected PrefixMatcher createMatcher(String prefix, boolean typoTolerant) {
        return createMatcher(prefix, true, typoTolerant);
    }

    @NotNull
    private static CamelHumpMatcher createMatcher(String prefix, boolean caseSensitive, boolean typoTolerant) {
        return new CamelHumpMatcher(prefix, caseSensitive, typoTolerant);
    }

    @Override
    public CompletionProcess getCurrentCompletion() {
        return myApiCompletionProcess;
    }

    @Override
    public CompletionSorter defaultSorter(CompletionParameters parameters, PrefixMatcher matcher) {
        return new CompletionSorter() {
            @Override
            public CompletionSorter weighBefore(@NotNull String beforeId, LookupElementWeigher... weighers) {
                return null;
            }

            @Override
            public CompletionSorter weighAfter(@NotNull String afterId, LookupElementWeigher... weighers) {
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
    protected CompletionResultSet createResultSet(CompletionParameters parameters, Consumer<? super CompletionResult> consumer,
                                                  @NotNull CompletionContributor contributor, PrefixMatcher matcher) {
        return new BaseCompletionResultSet(consumer, matcher, contributor, parameters, null, null);
    }

    protected static class BaseCompletionResultSet extends CompletionResultSet {

        protected final CompletionParameters myParameters;
        protected CompletionSorter mySorter;
        @Nullable
        protected final CompletionServiceImpl.BaseCompletionResultSet myOriginal;

        protected BaseCompletionResultSet(Consumer<? super CompletionResult> consumer, PrefixMatcher prefixMatcher,
                                          CompletionContributor contributor, CompletionParameters parameters,
                                          @Nullable CompletionSorter sorter, @Nullable CompletionServiceImpl.BaseCompletionResultSet original) {
            super(prefixMatcher, consumer, contributor);
            myParameters = parameters;
            mySorter = sorter;
            myOriginal = original;
        }

        @Override
        public void addElement(LookupElement element) {
            ProgressManager.checkCanceled();
            if (!element.isValid()) {
                LOG.error("Invalid lookup element: " + element + " of " + element.getClass() +
                        " in " + myParameters.getOriginalFile() + " of " + myParameters.getOriginalFile().getClass());
                return;
            }

            mySorter = mySorter == null ? getCompletionService().defaultSorter(myParameters, getPrefixMatcher()) : mySorter;

            CompletionResult matched = CompletionResult.wrap(element, getPrefixMatcher(), mySorter);
            if (matched != null) {
                element.putUserData(LOOKUP_ELEMENT_CONTRIBUTOR, myContributor);
                passResult(matched);
            }
        }

        @Override
        public CompletionResultSet withPrefixMatcher(PrefixMatcher matcher) {
            if (matcher.equals(getPrefixMatcher())) {
                return this;
            }
            return new BaseCompletionResultSet(getConsumer(), matcher, myContributor, myParameters, mySorter, this);
        }

        @Override
        public CompletionResultSet withPrefixMatcher(String prefix) {
            return withPrefixMatcher(getPrefixMatcher().cloneWithPrefix(prefix));
        }

        @Override
        public CompletionResultSet withRelevanceSorter(CompletionSorter sorter) {
            return new BaseCompletionResultSet(getConsumer(), getPrefixMatcher(), myContributor, myParameters, sorter, this);
        }

        @Override
        public void addLookupAdvertisement(String text) {
            getCompletionService().setAdvertisementText(text);
        }

        @Override
        public CompletionResultSet caseInsensitive() {
            PrefixMatcher matcher = getPrefixMatcher();
            boolean typoTolerant = matcher instanceof CamelHumpMatcher && ((CamelHumpMatcher)matcher).isTypoTolerant();
            return withPrefixMatcher(createMatcher(matcher.getPrefix(), false, typoTolerant));
        }

        @Override
        public void restartCompletionOnPrefixChange(ElementPattern<String> prefixCondition) {

        }

        @Override
        public void restartCompletionWhenNothingMatches() {

        }
    }
}
