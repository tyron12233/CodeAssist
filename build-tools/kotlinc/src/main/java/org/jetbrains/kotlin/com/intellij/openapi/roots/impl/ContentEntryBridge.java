package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ExcludeFolder;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ContentEntryBridge implements ContentEntry {

    private final ModuleRootModel model;
    private final VirtualFile file;
    private final List<SourceFolder> sourceFolders;

    public ContentEntryBridge(ModuleRootModel model, VirtualFile file, List<SourceFolder> sourceFolders) {
        this.model = model;
        this.file = file;
        this.sourceFolders = sourceFolders;
    }


    @Nullable
    @Override
    public VirtualFile getFile() {
        return file;
    }

    @NonNull
    @Override
    public String getUrl() {
        return file.getUrl();
    }

    @Override
    public SourceFolder[] getSourceFolders() {
        return sourceFolders.toArray(SourceFolder[]::new);
    }

    @Override
    public VirtualFile[] getSourceFolderFiles() {
        return Arrays.stream(getSourceFolders())
                .map(SourceFolder::getFile)
                .toArray(VirtualFile[]::new);
    }

    @NonNull
    @Override
    public ExcludeFolder[] getExcludeFolders() {
        return new ExcludeFolder[0];
    }

    @NonNull
    @Override
    public List<String> getExcludeFolderUrls() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public VirtualFile[] getExcludeFolderFiles() {
        return new VirtualFile[0];
    }

    @NonNull
    @Override
    public SourceFolder addSourceFolder(@NonNull VirtualFile file, boolean isTestSource) {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public SourceFolder addSourceFolder(@NonNull VirtualFile file,
                                        boolean isTestSource,
                                        @NonNull String packagePrefix) {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public SourceFolder addSourceFolder(@NonNull String url, boolean isTestSource) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeSourceFolder(@NonNull SourceFolder sourceFolder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearSourceFolders() {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public ExcludeFolder addExcludeFolder(@NonNull VirtualFile file) {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public ExcludeFolder addExcludeFolder(@NonNull String url) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeExcludeFolder(@NonNull ExcludeFolder excludeFolder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeExcludeFolder(@NonNull String url) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearExcludeFolders() {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public List<String> getExcludePatterns() {
        return Collections.emptyList();
    }

    @Override
    public void addExcludePattern(@NonNull String pattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeExcludePattern(@NonNull String pattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setExcludePatterns(@NonNull List<String> patterns) {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public ModuleRootModel getRootModel() {
        return model;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }
}
