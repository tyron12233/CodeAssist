package com.tyron.psi.lookup;

import com.tyron.psi.completion.PrefixMatcher;

import org.jetbrains.annotations.NotNull;

/**
 * @author peter
 */
public interface WeighingContext {
    @NotNull
    String itemPattern(@NotNull LookupElement element);

    @NotNull
    PrefixMatcher itemMatcher(@NotNull LookupElement item);

}
