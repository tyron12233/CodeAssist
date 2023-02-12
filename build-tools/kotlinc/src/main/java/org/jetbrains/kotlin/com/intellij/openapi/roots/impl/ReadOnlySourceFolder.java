package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public class ReadOnlySourceFolder implements SourceFolder {

    private final ContentEntry parent;
    private final VirtualFile file;
    private final boolean isTestSource;

    public ReadOnlySourceFolder(
            ContentEntry parent,
            VirtualFile file,
            boolean isTestSource
    ) {
        this.parent = parent;
        this.file = file;
        this.isTestSource = isTestSource;
    }

    @Nullable
    @Override
    public VirtualFile getFile() {
        return file;
    }

    @NonNull
    @Override
    public ContentEntry getContentEntry() {
        return parent;
    }

    @NonNull
    @Override
    public String getUrl() {
        return file.getUrl();
    }

    @Override
    public boolean isTestSource() {
        return isTestSource;
    }

    @NonNull
    @Override
    public String getPackagePrefix() {
        return null;
    }

    @Override
    public void setPackagePrefix(@NonNull String packagePrefix) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }
}
