package org.gradle.api.internal.tasks.properties;


import org.gradle.internal.file.TreeType;

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