package com.tyron.xml.completion.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceValue;

public class SimpleResourceItem implements ResourceItem {

    private final ResourceValue mValue;

    private FolderConfiguration mConfiguration;

    public SimpleResourceItem(ResourceValue value, String folderName) {
        mValue = value;
        mConfiguration = FolderConfiguration.getConfigForFolder(folderName);
    }

    @NotNull
    @Override
    public FolderConfiguration getConfiguration() {
        return mConfiguration;
    }

    @NotNull
    @Override
    public String getName() {
        return mValue.getName();
    }

    @NotNull
    @Override
    public ResourceType getType() {
        return mValue.getResourceType();
    }

    @NotNull
    @Override
    public ResourceNamespace getNamespace() {
        return mValue.getNamespace();
    }

    @Nullable
    @Override
    public String getLibraryName() {
        return mValue.getLibraryName();
    }

    @NotNull
    @Override
    public ResourceReference getReferenceToSelf() {
        return mValue.asReference();
    }

    @NotNull
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
