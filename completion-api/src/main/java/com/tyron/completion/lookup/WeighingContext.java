package com.tyron.completion.lookup;

import com.tyron.completion.PrefixMatcher;

import org.jetbrains.annotations.NotNull;

public interface WeighingContext {
  @NotNull
  String itemPattern(@NotNull LookupElement element);

  @NotNull PrefixMatcher itemMatcher(@NotNull LookupElement item);

}