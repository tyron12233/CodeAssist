package com.tyron.completion.impl;

import com.tyron.completion.CompletionService;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementWeigher;
import com.tyron.completion.lookup.WeighingContext;

import org.jetbrains.annotations.NotNull;

public class PreferStartMatching extends LookupElementWeigher {

  public PreferStartMatching() {
    super("middleMatching", false, true);
  }

  @Override
  public Comparable weigh(@NotNull LookupElement element, @NotNull WeighingContext context) {
    return !CompletionService.isStartMatch(element, context);
  }
}