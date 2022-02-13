package com.tyron.completion.xml.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;

public class SimpleResourceItem implements ResourceItem {

    private final ResourceValue mValue;

    private FolderConfiguration mConfiguration;

    public SimpleResourceItem(ResourceValue value, String folderName) {
        mValue = value;
        mConfiguration = FolderConfiguration.getConfigForFolder(folderName);
    }

    @NonNull
    @Override
    public FolderConfiguration getConfiguration() {
        return mConfiguration;
    }

    @NonNull
    @Override
    public String getName() {
        return mValue.getName();
    }

    @NonNull
    @Override
    public ResourceType getType() {
        return mValue.getResourceType();
    }

    @NonNull
    @Override
    public ResourceNamespace getNamespace() {
        return mValue.getNamespace();
    }

    @Nullable
    @Override
    public String getLibraryName() {
        return mValue.getLibraryName();
    }

    @NonNull
    @Override
    public ResourceReference getReferenceToSelf() {
        return mValue.asReference();
    }

    @NonNull
    @Override
    public String getKey() {
        return mValue.getName();
    }

    @Nullable
    @Override
    public ResourceValue getResourceValue() {
        return mValue;
    }

    @Nullable
    @Override
    public String getSource() {
        return null;
    }

    @Override
    public boolean isFileBased() {
        return false;
    }
}
