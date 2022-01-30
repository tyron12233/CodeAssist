package com.tyron.completion.model;

import com.tyron.completion.CompletionProvider;

import java.util.List;
import java.util.ArrayList;

/**
 * Represents a list of completion items to be return from a {@link CompletionProvider}
 */
public class CompletionList {

    public static final CompletionList EMPTY = new CompletionList();

    public boolean isIncomplete = false;

    public List<CompletionItem> items = new ArrayList<>();

    /**
     * For performance reasons, the completion items are limited to a certain amount.
     * A completion provider may indicate that its results are incomplete so next as
     * the user is typing the prefix the completion system will not cache this results.
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
}
