package org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public abstract class LibraryScopeBase extends GlobalSearchScope {
    private final Object2IntMap<VirtualFile> myEntries;
    // Maps each classpath root to its position in the classpath.
    protected final FileIndexFacade myIndex;

    public LibraryScopeBase(Project project, VirtualFile[] classes, VirtualFile[] sources) {
        super(project);
        myIndex = FileIndexFacade.getInstance(project);
        myEntries = new Object2IntOpenHashMap<VirtualFile>(classes.length + sources.length) {
            @Override
            public int defaultReturnValue() {
                return Integer.MAX_VALUE;
            }
        };
        for (VirtualFile file : classes) {
            myEntries.put(file, myEntries.size());
        }
        for (VirtualFile file : sources) {
            myEntries.put(file, myEntries.size());
        }
    }

    @Override
    public boolean contains(@NonNull VirtualFile file) {
        return myEntries.containsKey(getFileRoot(file));
    }

    public List<VirtualFile> getFiles() {
        return new ArrayList<>(myEntries.keySet());
    }

    @Nullable
    protected VirtualFile getFileRoot(@NonNull VirtualFile file) {
        return getFiles().stream()
                .filter(root -> VfsUtilCore.isAncestor(root, file, false))
                .findAny()
                .orElse(null);
    }

    @Override
    public int compare(@NonNull VirtualFile file1, @NonNull VirtualFile file2) {
        int pos1 = myEntries.getInt(getFileRoot(file1));
        int pos2 = myEntries.getInt(getFileRoot(file2));
        return Integer.compare(pos2, pos1);
    }

    public boolean isSearchInModuleContent(@NonNull Module aModule) {
        return false;
    }

    @Override
    public boolean isSearchInLibraries() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LibraryScopeBase)) {
            return false;
        }

        return myEntries.keySet().equals(((LibraryScopeBase) o).myEntries.keySet());
    }

    @Override
    public int calcHashCode() {
        return myEntries.keySet().hashCode();
    }
}