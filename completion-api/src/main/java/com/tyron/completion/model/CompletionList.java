package com.tyron.completion.model;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.errorprone.annotations.Immutable;
import com.tyron.completion.CompletionPrefixMatcher;
import com.tyron.completion.CompletionPrefixMatcher.MatchLevel;
import com.tyron.completion.CompletionProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a list of completion items to be return from a {@link CompletionProvider}
 */
@Immutable
public class CompletionList {

    public static final Ordering<CompletionItem> ITEM_ORDERING =
            Ordering.from(CompletionItem.COMPARATOR);

    public static Builder builder(String prefix) {
        return new Builder(prefix);
    }

    public static final CompletionList EMPTY = new CompletionList();

    public boolean isIncomplete = false;

    public List<CompletionItem> items = new ArrayList<>();

    /**
     * For performance reasons, the completion items are limited to a certain amount.
     * A completion provider may indicate that its results are incomplete so next as
     * the user is typing the prefix the completion system will not cache this results.
     *
     * @return whether the list is incomplete
     */
    public boolean isIncomplete() {
        return isIncomplete;
    }

    public void setIncomplete(boolean incomplete) {
        isIncomplete = incomplete;
    }

    public List<CompletionItem> getItems() {
        return items;
    }

    public static CompletionList copy(CompletionList old, String newPrefix) {
        Builder builder = CompletionList.builder(newPrefix);
        if (old.isIncomplete) {
            builder.incomplete();
        }
        builder.addItems(old.getItems());
        return builder.build();
    }

    public static class Builder {
        private final List<CompletionItem> items;
        private boolean incomplete;

        private final String completionPrefix;

        public Builder(String completionPrefix) {
            items = new ArrayList<>();
            this.completionPrefix = completionPrefix;
        }

        public String getPrefix() {
            return completionPrefix;
        }

        public Builder addItems(Collection<CompletionItem> items) {
            for (CompletionItem item : items) {
                addItem(item);
            }
            return this;
        }

        public Builder addItems(Collection<CompletionItem> items, String sortText) {
            for (CompletionItem item : items) {
                item.setSortText(sortText);
                addItem(item);
            }
            return this;
        }

        public Builder addItem(CompletionItem item) {
            List<MatchLevel> matchLevels = new ArrayList<>();
            for (String filterText : item.getFilterTexts()) {
                MatchLevel matchLevel =
                        CompletionPrefixMatcher.computeMatchLevel(filterText, completionPrefix);
                if (matchLevel == MatchLevel.NOT_MATCH) {
                    continue;
                }
                matchLevels.add(matchLevel);
            }
            if (matchLevels.isEmpty()) {
                return this;
            }
            Collections.sort(matchLevels);
            MatchLevel matchLevel = matchLevels.get(matchLevels.size() - 1);
            item.setMatchLevel(matchLevel);
            items.add(item);
            return this;
        }

        public int getItemCount() {
            return items.size();
        }

        public void incomplete() {
            this.incomplete = true;
        }

        public boolean isIncomplete() {
            return incomplete;
        }

        @SuppressWarnings("NewApi")
        public CompletionList build() {
            CompletionList list = new CompletionList();
            list.isIncomplete = this.incomplete;
            list.items = ITEM_ORDERING.immutableSortedCopy(items);
            return list;
        }
    }
}
