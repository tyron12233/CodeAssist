package com.tyron.completion.model;

import android.graphics.drawable.Drawable;

import com.tyron.completion.CompletionPrefixMatcher;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.widget.CodeEditor;

public abstract class CompletionItemWithMatchLevel extends CompletionItem {

    private final List<String> filterTexts = new ArrayList<>(3);

    private CompletionPrefixMatcher.MatchLevel matchLevel = CompletionPrefixMatcher.MatchLevel.NOT_MATCH;

    public CompletionItemWithMatchLevel(CharSequence label) {
        this(label, null);
    }

    public CompletionItemWithMatchLevel(CharSequence label, CharSequence desc) {
        this(label, desc, null);
    }

    public CompletionItemWithMatchLevel(CharSequence label, CharSequence desc, Drawable icon) {
        super(label, desc, icon);

        sortText = "";
    }

    public CompletionItemWithMatchLevel matchLevel(CompletionPrefixMatcher.MatchLevel level) {
        this.matchLevel = level;
        return this;
    }

    public CompletionPrefixMatcher.MatchLevel getMatchLevel() {
        return matchLevel;
    }

    public void addFilterText(String text) {
        filterTexts.add(text);
    }

    public List<String> getFilterTexts() {
        return filterTexts;
    }
}
