package com.tyron.completion.model;

import java.util.List;
import java.util.Objects;

public class CompletionItem {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompletionItem)) return false;
        CompletionItem that = (CompletionItem) o;
        return cursorOffset == that.cursorOffset
                && Objects.equals(label, that.label)
                && Objects.equals(detail, that.detail)
                && Objects.equals(commitText, that.commitText)
                && action == that.action
                && iconKind == that.iconKind
                && Objects.equals(additionalTextEdits, that.additionalTextEdits)
                && Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, detail, commitText, action, iconKind, cursorOffset, additionalTextEdits, data);
    }

    public enum Kind {
        OVERRIDE,
        IMPORT,
        NORMAL
    }

    // The string that would be shown to the user
    public String label;

    public String detail;

    public String commitText;

    public Kind action = Kind.NORMAL;

    public DrawableKind iconKind = DrawableKind.Method;

    public int cursorOffset = -1;

    public List<TextEdit> additionalTextEdits;

    public String data = "";

    public CompletionItem() {

    }

    public CompletionItem(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
