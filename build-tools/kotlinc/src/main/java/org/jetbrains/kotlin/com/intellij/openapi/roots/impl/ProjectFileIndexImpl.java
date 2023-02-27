package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class ProjectFileIndexImpl extends FileIndexBase implements ProjectFileIndex {

    private static final Logger LOG = Logger.getInstance(ProjectFileIndexImpl.class);
    private final Project myProject;

    public ProjectFileIndexImpl(@NotNull Project project) {
        super(project);
        myProject = project;
    }

    @Override
    public boolean iterateContent(@NotNull ContentIterator processor, @Nullable VirtualFileFilter filter) {
        Module[] modules = ReadAction.compute(() -> ModuleManager.getInstance(myProject).getModules());
        for (final Module module : modules) {
            for (VirtualFile contentRoot : getRootsToIterate(module)) {
                if (!iterateContentUnderDirectory(contentRoot, processor, filter)) {
                    return false;
                }
            }
        }
        return true;
    }

    @NotNull
    private Set<VirtualFile> getRootsToIterate(@NotNull Module module) {
        return ReadAction.compute(() -> {
            if (module.isDisposed()) return Collections.emptySet();

            ModuleFileIndexImpl moduleFileIndex = (ModuleFileIndexImpl) ModuleRootManager.getInstance(module).getFileIndex();
            Set<VirtualFile> result = moduleFileIndex.getModuleRootsToIterate();

            for (Iterator<VirtualFile> iterator = result.iterator(); iterator.hasNext(); ) {
                VirtualFile root = iterator.next();
                Module moduleForFile = getModuleForFile(root, false);
                if (!module.equals(moduleForFile)) { // maybe 2 modules have the same content root?
                    iterator.remove();
                    continue;
                }

                VirtualFile parent = root.getParent();
                if (parent != null) {
                    if (isInContent(parent)) {
                        iterator.remove();
                    }
                }
            }

            return result;
        });
    }

    @Override
    public boolean isExcluded(@NotNull VirtualFile file) {
        DirectoryInfo info = getInfoForFileOrDirectory(file);
        return info.isIgnored() || info.isExcluded(file);
    }

    @Override
    public boolean isUnderIgnored(@NonNull VirtualFile file) {
        return getInfoForFileOrDirectory(file).isIgnored();
    }

    @Override
    public boolean isInGeneratedSources(@NonNull VirtualFile file) {
        Logger.getInstance("UNIMPLEMENTED").warn("isInGeneratedSources is not yet implemented");
        return false;
    }

    @Override
    public boolean isInProject(@NonNull VirtualFile file) {
        return getInfoForFileOrDirectory(file).isInProject(file);
    }

    @Override
    public boolean isInProjectOrExcluded(@NonNull VirtualFile file) {
        DirectoryInfo directoryInfo = getInfoForFileOrDirectory(file);
        return directoryInfo.isInProject(file) || directoryInfo.isExcluded(file);
    }

    @Nullable
    @Override
    public Module getModuleForFile(@NonNull VirtualFile file) {
        return getModuleForFile(file, true);
    }

    @Nullable
    @Override
    public Module getModuleForFile(@NonNull VirtualFile file, boolean honorExclusion) {
        DirectoryInfo info = getInfoForFileOrDirectory(file);
        if (info.isInProject(file) || !honorExclusion && info.isExcluded(file)) {
            return info.getModule();
        }
        return null;
    }

    @Override
    @NotNull
    public List<OrderEntry> getOrderEntriesForFile(@NotNull VirtualFile file) {
        return myDirectoryIndex.getOrderEntries(file);
    }

    @Nullable
    @Override
    public VirtualFile getClassRootForFile(@NonNull VirtualFile file) {
        return getClassRootForFile(file, getInfoForFileOrDirectory(file));
    }

    @Nullable
    public static VirtualFile getClassRootForFile(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {
        return info.isInProject(file) ? info.getLibraryClassRoot() : null;
    }

    public static boolean isFileInContent(@NotNull VirtualFile fileOrDir, @NotNull DirectoryInfo info) {
        return info.isInProject(fileOrDir) && info.getModule() != null;
    }

    @Nullable
    @Override
    public VirtualFile getSourceRootForFile(@NonNull VirtualFile file) {
        return getSourceRootForFile(file, getInfoForFileOrDirectory(file));
    }

    @Nullable
    public static VirtualFile getSourceRootForFile(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {
        return info.isInProject(file) ? info.getSourceRoot() : null;
    }

    @Override
    public VirtualFile getContentRootForFile(@NotNull VirtualFile file) {
        return getContentRootForFile(file, true);
    }

    @Override
    public VirtualFile getContentRootForFile(@NotNull VirtualFile file, final boolean honorExclusion) {
        return getContentRootForFile(getInfoForFileOrDirectory(file), file, honorExclusion);
    }

    @Nullable
    public static VirtualFile getContentRootForFile(@NotNull DirectoryInfo info, @NotNull VirtualFile file, boolean honorExclusion) {
        if (info.isInProject(file) || !honorExclusion && info.isExcluded(file)) {
            return info.getContentRoot();
        }
        return null;
    }

    @Override
    public String getPackageNameByDirectory(@NotNull VirtualFile dir) {
        if (!dir.isDirectory()) LOG.error(dir.getPresentableUrl());
        return myDirectoryIndex.getPackageName(dir);
    }

    @Override
    public boolean isLibraryClassFile(@NotNull VirtualFile file) {
        if (file.isDirectory()) return false;
        DirectoryInfo parentInfo = getInfoForFileOrDirectory(file);
        return parentInfo.isInProject(file) && parentInfo.hasLibraryClassRoot();
    }

    @Override
    public boolean isInSource(@NotNull VirtualFile fileOrDir) {
        DirectoryInfo info = getInfoForFileOrDirectory(fileOrDir);
        return info.isInModuleSource(fileOrDir) || info.isInLibrarySource(fileOrDir);
    }

    @Override
    public boolean isInLibraryClasses(@NotNull VirtualFile fileOrDir) {
        DirectoryInfo info = getInfoForFileOrDirectory(fileOrDir);
        return info.isInProject(fileOrDir) && info.hasLibraryClassRoot();
    }

    @Override
    public boolean isInLibrarySource(@NotNull VirtualFile fileOrDir) {
        DirectoryInfo info = getInfoForFileOrDirectory(fileOrDir);
        return info.isInProject(fileOrDir) && info.isInLibrarySource(fileOrDir);
    }

    // a slightly faster implementation then the default one
    @Override
    public boolean isInLibrary(@NotNull VirtualFile fileOrDir) {
        DirectoryInfo info = getInfoForFileOrDirectory(fileOrDir);
        return info.isInProject(fileOrDir) && (info.hasLibraryClassRoot() || info.isInLibrarySource(fileOrDir));
    }

    @Override
    public boolean isIgnored(@NotNull VirtualFile file) {
        return isExcluded(file);
    }

    @Override
    public boolean isInContent(@NotNull VirtualFile fileOrDir) {
        return isFileInContent(fileOrDir, getInfoForFileOrDirectory(fileOrDir));
    }

    public @Nullable VirtualFile getModuleSourceOrLibraryClassesRoot(@NotNull VirtualFile file) {
        DirectoryInfo info = getInfoForFileOrDirectory(file);
        if (isFileInContent(file, info)) {
            return getSourceRootForFile(file, info);
        }
        return getClassRootForFile(file, info);
    }

    @Override
    public boolean isInSourceContent(@NotNull VirtualFile fileOrDir) {
        return getInfoForFileOrDirectory(fileOrDir).isInModuleSource(fileOrDir);
    }

    @Override
    public boolean isInTestSourceContent(@NotNull VirtualFile fileOrDir) {
        DirectoryInfo info = getInfoForFileOrDirectory(fileOrDir);
        return info.isInModuleSource(fileOrDir) && isTestSourcesRoot(info);
    }

    @Nullable
    @Override
    public SourceFolder getSourceFolder(@NotNull VirtualFile fileOrDir) {
        return myDirectoryIndex.getSourceRootFolder(getInfoForFileOrDirectory(fileOrDir));
    }

    @Override
    public @Nullable String getUnloadedModuleNameForFile(@NotNull VirtualFile fileOrDir) {
        return getInfoForFileOrDirectory(fileOrDir).getUnloadedModuleName();
    }

    @Override
    protected boolean isScopeDisposed() {
        return myProject.isDisposed();
    }
}
