package com.tyron.completion.model;

import com.google.common.collect.ImmutableList;
import com.tyron.completion.CompletionPrefixMatcher;
import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.InsertHandler;
import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A class representing the completion item shown in the user list
 */
public class CompletionItem implements Comparable<CompletionItem> {

    @SuppressWarnings("NewApi")
    public static final Comparator<CompletionItem> COMPARATOR =
            Comparator.comparing((CompletionItem item) -> item.getMatchLevel()
                    .ordinal(), Comparator.reverseOrder())
                    .thenComparing(CompletionItem::getSortText)
                    .thenComparing(it -> it.getFilterTexts()
                            .isEmpty() ? it.getLabel() : it.getFilterTexts()
                            .get(0));


    public static CompletionItem create(String label, String detail, String commitText) {
        return create(label, detail, commitText, null);
    }

    public static CompletionItem create(String label, String detail, String commitText,
                                        DrawableKind kind) {
        CompletionItem completionItem = new CompletionItem(label, detail, commitText, kind);
        completionItem.sortText = "";
        completionItem.matchLevel = CompletionPrefixMatcher.MatchLevel.NOT_MATCH;
        return completionItem;
    }

    private InsertHandler insertHandler;
    public String label;
    public String detail;
    public String commitText;
    public Kind action = Kind.NORMAL;
    public DrawableKind iconKind = DrawableKind.Method;
    public int cursorOffset = -1;
    public List<TextEdit> additionalTextEdits;
    public String data = "";

    private String sortText;
    private List<String> filterTexts = new ArrayList<>(1);
    private CompletionPrefixMatcher.MatchLevel matchLevel;

    public CompletionItem() {
        this.insertHandler = new DefaultInsertHandler(CompletionUtils.JAVA_PREDICATE, this);
        this.sortText = "";
    }

    public CompletionItem(String label, String details, String commitText, DrawableKind kind) {
        this.label = label;
        this.detail = details;
        this.commitText = commitText;
        this.cursorOffset = commitText.length();
        this.iconKind = kind;
        this.insertHandler = new DefaultInsertHandler(CompletionUtils.JAVA_PREDICATE, this);
        this.sortText = "";
    }

    public CompletionItem(String label) {
        this.label = label;
    }

    public void setSortText(String sortText) {
        this.sortText = sortText;
    }

    public ImmutableList<String> getFilterTexts() {
        if (filterTexts.isEmpty()) {
            return ImmutableList.of(label);
        }
        return ImmutableList.copyOf(filterTexts);
    }

    public void addFilterText(String text) {
        filterTexts.add(text);
    }

    public String getSortText() {
        return sortText;
    }

    public CompletionPrefixMatcher.MatchLevel getMatchLevel() {
        return matchLevel;
    }

    public String getLabel() {
        return label;
    }

    public void setMatchLevel(CompletionPrefixMatcher.MatchLevel matchLevel) {
        this.matchLevel = matchLevel;
    }

    public enum Kind {
        OVERRIDE, IMPORT, NORMAL
    }

    public void setInsertHandler(InsertHandler handler) {
        this.insertHandler = handler;
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompletionItem)) {
            return false;
        }
        CompletionItem that = (CompletionItem) o;
        return cursorOffset == that.cursorOffset &&
               Objects.equals(label, that.label) &&
               Objects.equals(detail, that.detail) &&
               Objects.equals(commitText, that.commitText) &&
               action == that.action &&
               iconKind == that.iconKind &&
               Objects.equals(additionalTextEdits, that.additionalTextEdits) &&
               Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, detail, commitText, action, iconKind, cursorOffset,
                            additionalTextEdits, data);
    }


    @Override
    public int compareTo(CompletionItem o) {
        return COMPARATOR.compare(this, o);
    }


    public void handleInsert(Editor editor) {
        insertHandler.handleInsert(editor);
    }
}
