package org.gradle.api.internal.tasks.properties;

import org.gradle.api.internal.file.FileCollectionFactory;

public class GetOutputFilesVisitor extends PropertyVisitor.Adapter {

    private final String ownerDisplayName;
    private final FileCollectionFactory fileCollectionFactory;

    public GetOutputFilesVisitor(String ownerDisplayName, FileCollectionFactory fileCollectionFactory) {
        this.ownerDisplayName = ownerDisplayName;
        this.fileCollectionFactory = fileCollectionFactory;
    }
}
