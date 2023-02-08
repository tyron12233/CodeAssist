package com.tyron.completion;

import com.tyron.completion.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;

/**
 * @see CompletionContributor
 * @see PrioritizedLookupElement
 */
public abstract class CompletionWeigher extends Weigher<LookupElement, CompletionLocation> {

  @Override
  public abstract Comparable weigh(@NotNull final LookupElement element, @NotNull final CompletionLocation location);
}