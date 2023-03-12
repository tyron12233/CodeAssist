package com.tyron.completion.model;

import androidx.annotation.RequiresApi;

import com.google.common.collect.Ordering;
import com.google.errorprone.annotations.Immutable;
import com.tyron.completion.CompletionPrefixMatcher;
import com.tyron.completion.CompletionPrefixMatcher.MatchLevel;
import com.tyron.completion.legacy.CompletionProvider;
import com.tyron.completion.lookup.LookupElement;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a list of completion items to be return from a {@link CompletionProvider}
 */
@Immutable
@RequiresApi(24)
public class CompletionList {

    public static final Comparator<CompletionItemWithMatchLevel> COMPARATOR =
            Comparator.comparing((CompletionItemWithMatchLevel item) -> item.getMatchLevel()
                            .ordinal(), Comparator.reverseOrder())
                    .thenComparing((CompletionItemWithMatchLevel it) -> it.sortText)
                    .thenComparing((CompletionItemWithMatchLevel it) -> it.getFilterTexts()
                            .isEmpty() ? it.label.toString() : it.getFilterTexts()
                            .get(0));
    public static final Ordering<CompletionItemWithMatchLevel> ITEM_ORDERING =
            Ordering.from(COMPARATOR);
    private String prefix;

    public static Builder builder(String prefix) {
        return new Builder(prefix);
    }

    public static final CompletionList EMPTY = new CompletionList();

    public boolean isIncomplete = false;

    public List<CompletionItemWithMatchLevel> items = new ArrayList<>();

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

    public List<CompletionItemWithMatchLevel> getItems() {
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

    public String getPrefix() {
        return prefix;
    }

    public static class Builder {
        private final List<CompletionItemWithMatchLevel> items;
        private boolean incomplete;

        private String completionPrefix;

        private final Consumer<LookupElement> consumer;

        public Builder(String completionPrefix) {
            items = new ArrayList<>();
            this.completionPrefix = completionPrefix;
            this.consumer = (s) -> {};
        }

        public Builder(Consumer<LookupElement> consumer) {
            items = new ArrayList<>();
            this.consumer = consumer;
        }

        public void setCompletionPrefix(String prefix) {
            this.completionPrefix = prefix;
        }


        public String getPrefix() {
            return completionPrefix;
        }

        public Builder addItems(Collection<? extends CompletionItem> items) {
            for (CompletionItem item : items) {
                addItem(item);
            }
            return this;
        }

        public Builder addItems(Collection<CompletionItem> items, String sortText) {
            for (CompletionItem item : items) {
                item.sortText = sortText;
                addItem(item);
            }
            return this;
        }

        public Builder addItem(CompletionItem i) {
//            LookupElement element = null;
//
//            List<MatchLevel> matchLevels = new ArrayList<>();
//            List<String> filterTexts = element.getFilterTexts();
//            if (filterTexts.isEmpty()) {
//                filterTexts = Collections.singletonList(element.getLookupString());
//            }
//            for (String filterText : filterTexts) {
//                MatchLevel matchLevel =
//                        CompletionPrefixMatcher.computeMatchLevel(filterText, completionPrefix);
//                if (matchLevel == MatchLevel.NOT_MATCH) {
//                    continue;
//                }
//                matchLevels.add(matchLevel);
//            }
//            if (matchLevels.isEmpty()) {
//                return this;
//            }
//            Collections.sort(matchLevels);
//            MatchLevel matchLevel = matchLevels.get(matchLevels.size() - 1);
//            element.matchLevel(matchLevel);
//
//            consumer.accept(element);
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
            list.prefix = completionPrefix;
            return list;
        }
    }
}
