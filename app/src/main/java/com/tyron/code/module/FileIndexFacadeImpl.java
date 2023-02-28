package com.tyron.code.module;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.VfsUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class FileIndexFacadeImpl extends FileIndexFacade {

    private final ProjectFileIndex myIndex;
    public FileIndexFacadeImpl(@NonNull Project project) {
        super(project);

        myIndex = ProjectFileIndex.getInstance(project);
    }

    @Override
    public boolean isInContent(@NonNull VirtualFile virtualFile) {
        return myIndex.isInContent(virtualFile);
    }

    @Override
    public boolean isInSource(@NonNull VirtualFile virtualFile) {
        return myIndex.isInSource(virtualFile);
    }

    @Override
    public boolean isInSourceContent(@NonNull VirtualFile virtualFile) {
        return myIndex.isInSourceContent(virtualFile);
    }

    @Override
    public boolean isInLibraryClasses(@NonNull VirtualFile virtualFile) {
        return myIndex.isInLibraryClasses(virtualFile);
    }

    @Override
    public boolean isInLibrarySource(@NonNull VirtualFile virtualFile) {
        return myIndex.isInLibrarySource(virtualFile);
    }

    @Override
    public boolean isExcludedFile(@NonNull VirtualFile virtualFile) {
        return myIndex.isExcluded(virtualFile);
    }

    @Override
    public boolean isUnderIgnored(@NonNull VirtualFile virtualFile) {
        return myIndex.isUnderIgnored(virtualFile);
    }

    @Override
    public @Nullable Module getModuleForFile(@NonNull VirtualFile virtualFile) {
        return myIndex.getModuleForFile(virtualFile);
    }

    @Override
    public @NonNull ModificationTracker getRootModificationTracker() {
        return ModificationTracker.NEVER_CHANGED;
    }

    @Override
    public @NonNull Collection<Object> getUnloadedModuleDescriptions() {
        return Collections.emptyList();
    }
}
