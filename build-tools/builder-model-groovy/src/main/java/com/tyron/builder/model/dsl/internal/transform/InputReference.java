package com.tyron.builder.model.dsl.internal.transform;

public class InputReference {
    private final String path;
    private final int lineNumber;

    public InputReference(String path, int lineNumber) {
        this.path = path;
        this.lineNumber = lineNumber;
    }

    public String getPath() {
        return path;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
