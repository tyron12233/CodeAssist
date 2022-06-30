package com.tyron.builder.api.internal.tasks.properties;


import com.tyron.builder.internal.file.TreeType;

public enum OutputFilePropertyType {
    FILE(TreeType.FILE),
    DIRECTORY(TreeType.DIRECTORY),
    FILES(TreeType.FILE),
    DIRECTORIES(TreeType.DIRECTORY);

    private final TreeType outputType;

    OutputFilePropertyType(TreeType outputType) {
        this.outputType = outputType;
    }

    public TreeType getOutputType() {
        return outputType;
    }
}