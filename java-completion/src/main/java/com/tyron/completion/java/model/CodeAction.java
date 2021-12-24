package com.tyron.completion.java.model;

import androidx.annotation.NonNull;

import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class CodeAction {

    private String title;
    private Map<Path, List<TextEdit>> edits;

    public static CodeAction NONE = new CodeAction();

    public Map<Path, List<TextEdit>> getEdits() {
        return edits;
    }

    public void setEdits(Map<Path, List<TextEdit>> edits) {
        this.edits = edits;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @NonNull
    @Override
    public String toString() {
        return title + " : " + edits.values();
    }
}
