package com.tyron.completion.model;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.InsertHandler;
import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Editor;

import java.util.List;
import java.util.Objects;

/**
 * A class representing the completion item shown in the user list
 */
public class CompletionItem {

    public static CompletionItem create(String label, String detail, String commitText) {
        return create(label, detail, commitText, null);
    }

    public static CompletionItem create(String label, String detail, String commitText, DrawableKind kind) {
        return new CompletionItem(label, detail, commitText, kind);
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

    public CompletionItem() {

    }

    public CompletionItem(String label, String details, String commitText, DrawableKind kind) {
        this.label = label;
        this.detail = details;
        this.commitText = commitText;
        this.cursorOffset = commitText.length();
        this.iconKind = kind;
        this.insertHandler = new DefaultInsertHandler(CompletionUtils.JAVA_PREDICATE, this);
    }

    public CompletionItem(String label) {
        this.label = label;
    }


    public enum Kind {
        OVERRIDE,
        IMPORT,
        NORMAL
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

    public void handleInsert(Editor editor) {
        insertHandler.handleInsert(editor);
    }
}
