package com.tyron.completion.impl;

import com.tyron.completion.CompletionService;
import com.tyron.completion.PrefixMatcher;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementWeigher;
import com.tyron.completion.lookup.WeighingContext;

import org.jetbrains.annotations.NotNull;

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
}