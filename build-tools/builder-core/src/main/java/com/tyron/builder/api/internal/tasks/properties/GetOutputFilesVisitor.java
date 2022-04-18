package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileCollectionFactory;

public class GetOutputFilesVisitor extends PropertyVisitor.Adapter {

    private final String ownerDisplayName;
    private final FileCollectionFactory fileCollectionFactory;

    public GetOutputFilesVisitor(String ownerDisplayName, FileCollectionFactory fileCollectionFactory) {
        this.ownerDisplayName = ownerDisplayName;
        this.fileCollectionFactory = fileCollectionFactory;
    }
}
