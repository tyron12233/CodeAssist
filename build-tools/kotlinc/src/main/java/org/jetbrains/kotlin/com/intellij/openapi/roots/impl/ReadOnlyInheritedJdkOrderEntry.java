package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.roots.InheritedJdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootPolicy;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;

import java.util.Arrays;

public class ReadOnlyInheritedJdkOrderEntry implements InheritedJdkOrderEntry {

    private final ModuleRootModel rootModel;
    private final Sdk sdk;

    public ReadOnlyInheritedJdkOrderEntry(ModuleRootModel rootModel, Sdk sdk) {

        this.rootModel = rootModel;
        this.sdk = sdk;
    }

    @Nullable
    @Override
    public Sdk getJdk() {
        return sdk;
    }

    @Nullable
    @Override
    public String getJdkName() {
        return sdk.getName();
    }

    @Override
    public VirtualFile[] getRootFiles(@NonNull OrderRootType type) {
        System.out.println("Get root files: " + type);
        if (type == OrderRootType.CLASSES) {
            VirtualFileSystem jfs = StandardFileSystems.jar();
            return sdk.getJarFiles().stream()
                    .map(it -> jfs.findFileByPath(it.getPath() + "!/"))
                    .toArray(VirtualFile[]::new);
        }
        return VirtualFile.EMPTY_ARRAY;
    }

    @Override
    public String[] getRootUrls(@NonNull OrderRootType type) {
        return Arrays.stream(getRootFiles(type))
                .map(VirtualFile::getUrl)
                .toArray(String[]::new);
    }

    @NonNull
    @Override
    public String getPresentableName() {
        return sdk.getName();
    }

    @Override
    public boolean isValid() {
        return !rootModel.getModule().isDisposed();
    }

    @NonNull
    @Override
    public Module getOwnerModule() {
        return rootModel.getModule();
    }

    @Override
    public <R> R accept(@NonNull RootPolicy<R> policy, @Nullable R initialValue) {
        return policy.visitInheritedJdkOrderEntry(this, initialValue);
    }

    @Override
    public int compareTo(OrderEntry orderEntry) {
        return 0;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }
}
