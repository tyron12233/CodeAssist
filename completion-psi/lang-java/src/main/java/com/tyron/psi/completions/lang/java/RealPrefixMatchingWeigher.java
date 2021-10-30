package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionService;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementWeigher;
import com.tyron.psi.lookup.WeighingContext;

import org.jetbrains.annotations.NotNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Peter
 */
public class RealPrefixMatchingWeigher extends LookupElementWeigher {

    public RealPrefixMatchingWeigher() {
        super("prefix", false, true);
    }

    @Override
    public Comparable weigh(@NotNull LookupElement element, @NotNull WeighingContext context) {
        return getBestMatchingDegree(element, CompletionService.getItemMatcher(element, context));
    }

    public static int getBestMatchingDegree(LookupElement element, PrefixMatcher matcher) {
        int max = Integer.MIN_VALUE;
        for (String lookupString : element.getAllLookupStrings()) {
            max = Math.max(max, matcher.matchingDegree(lookupString));
        }
        return max == Integer.MIN_VALUE ? Integer.MAX_VALUE : -max;
    }

    @Nullable
    @Override
    public Comparable weigh(LookupElement element) {
        return null;
    }
}
