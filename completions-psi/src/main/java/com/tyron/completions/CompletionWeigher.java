package com.tyron.completions;

import com.tyron.lookup.LookupElement;
import com.tyron.psi.Weigher;

import org.jetbrains.annotations.NotNull;

/**
 * @author peter
 * @see CompletionContributor
 * @see PrioritizedLookupElement
 */
public abstract class CompletionWeigher extends Weigher<LookupElement, CompletionLocation> {

    @Override
    public abstract Comparable weigh(@NotNull final LookupElement element, @NotNull final CompletionLocation location);
}
