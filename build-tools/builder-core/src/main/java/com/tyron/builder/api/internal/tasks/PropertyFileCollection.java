package com.tyron.builder.api.internal.tasks;


import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionInternal;

import java.util.function.Consumer;

public class PropertyFileCollection extends CompositeFileCollection {
    private final String ownerDisplayName;
    private final String type;
    private final String propertyName;
    private final FileCollectionInternal files;
    private String displayName;

    public PropertyFileCollection(String ownerDisplayName, String propertyName, String type, FileCollectionInternal files) {
        this.ownerDisplayName = ownerDisplayName;
        this.type = type;
        this.propertyName = propertyName;
        this.files = files;
    }

    @Override
    public String getDisplayName() {
        if (displayName == null) {
            displayName = type + " files for " + ownerDisplayName + " property '" + propertyName + "'";
        }
        return displayName;
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        visitor.accept(files);
    }
}