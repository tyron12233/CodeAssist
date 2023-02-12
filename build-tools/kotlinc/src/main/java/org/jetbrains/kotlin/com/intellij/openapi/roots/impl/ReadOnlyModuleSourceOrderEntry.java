package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleSourceOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootPolicy;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.Arrays;

public class ReadOnlyModuleSourceOrderEntry implements ModuleSourceOrderEntry {

    private final ModuleRootModel moduleRootModel;
    private final boolean includeTests;
    public ReadOnlyModuleSourceOrderEntry(ModuleRootModel moduleRootModel, boolean includeTests) {

        this.moduleRootModel = moduleRootModel;
        this.includeTests = includeTests;
    }

    @NonNull
    @Override
    public ModuleRootModel getRootModel() {
        return moduleRootModel;
    }

    @Override
    public VirtualFile[] getFiles(@NonNull OrderRootType type) {
        if (type == OrderRootType.SOURCES) {
            return moduleRootModel.getSourceRoots(includeTests);
        }
        return new VirtualFile[0];
    }

    @Override
    public String[] getUrls(@NonNull OrderRootType rootType) {
        return Arrays.stream(getFiles(rootType))
                .map(VirtualFile::getUrl)
                .toArray(String[]::new);
    }

    @NonNull
    @Override
    public String getPresentableName() {
        return "sourceFolder";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @NonNull
    @Override
    public Module getOwnerModule() {
        return getRootModel().getModule();
    }

    @Override
    public <R> R accept(@NonNull RootPolicy<R> policy, @Nullable R initialValue) {
        return policy.visitModuleSourceOrderEntry(this, initialValue);
    }

    @Override
    public int compareTo(@NonNull OrderEntry orderEntry) {
        return 0;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }
}
