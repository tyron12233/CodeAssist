package com.tyron.completion.impl;

import com.tyron.completion.CompletionResult;
import com.tyron.completion.CompletionSorter;
import com.tyron.completion.PrefixMatcher;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;
import com.tyron.completion.lookup.WeighingContext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;

import java.util.List;

/**
 * Determines the order of completion items and the initial selection.
 */
public interface CompletionLookupArranger {
  Key<WeighingContext> WEIGHING_CONTEXT = Key.create("WEIGHING_CONTEXT");
  Key<Integer> PREFIX_CHANGES = Key.create("PREFIX_CHANGES");

  /**
   * Adds an element to be arranged.
   * @param presentation The presentation of the element (rendered with {@link LookupElement#renderElement(LookupElementPresentation)}
   */
  void addElement(@NotNull LookupElement element,
                  @NotNull CompletionSorter sorter,
                  @NotNull PrefixMatcher prefixMatcher,
                  @NotNull LookupElementPresentation presentation);

  /**
   * Adds an element to be arranged, along with its prefix matcher.
   */
  void addElement(@NotNull CompletionResult result);

  /**
   * Returns the prefix matcher registered for the specified element.
   */
  PrefixMatcher itemMatcher(@NotNull LookupElement item);

  /**
   * Returns the items in the appropriate order and the initial selection.
   *
   * @return Pair where the first element is the sorted list of completion items and the second item is the index of the item to select
   *         initially.
   */
  Pair<List<LookupElement>, Integer> arrangeItems();
}