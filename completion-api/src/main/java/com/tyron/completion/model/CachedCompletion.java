package com.tyron.completion.model;

import com.tyron.completion.model.CompletionList;

import java.io.File;

public class CachedCompletion {
    private final File file;
    private final int line;
    private final int column;
    private final String prefix;
    private final CompletionList completionList;

    public CachedCompletion(File file, int line, int column, String prefix, CompletionList completionList) {
        this.file = file;
        this.line = line;
        this.column = column;
        this.prefix = prefix;
        this.completionList = completionList;
    }

    public File getFile() {
        return file;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getPrefix() {
        return prefix;
    }

    public CompletionList getCompletionList() {
        return completionList;
    }
}