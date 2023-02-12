package com.tyron.completion.roots;

import androidx.annotation.Nullable;

import com.tyron.builder.project.api.Module;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public interface  ProjectFileIndex {

    @NotNull
    static ProjectFileIndex getInstance(@NotNull Project project) {
        return project.getService(ProjectFileIndex.class);
    }

    /**
     * Returns module to which content the specified file belongs or null if the file does not belong to content of any module.
     */
    @Nullable
    Module getModuleForFile(@NotNull VirtualFile file);

    /**
     * Returns module to which content the specified file belongs or null if the file does not belong to content of any module.
     *
     * @param honorExclusion if {@code false} the containing module will be returned even if the file is located under a folder marked as excluded
     */
    @Nullable
    Module getModuleForFile(@NotNull VirtualFile file, boolean honorExclusion);

    /**
     * Returns a classpath entry to which the specified file or directory belongs.
     *
     * @return the file for the classpath entry, or null if the file is not a compiled
     *         class file or directory belonging to a library.
     */
    @Nullable
    VirtualFile getClassRootForFile(@NotNull VirtualFile file);

    /**
     * Returns the module source root or library source root to which the specified file or directory belongs.
     *
     * @return the file for the source root, or null if the file is not located under any of the source roots for the module.
     */
    @Nullable
    VirtualFile getSourceRootForFile(@NotNull VirtualFile file);

    /**
     * Returns the module content root to which the specified file or directory belongs or null if the file does not belong to content of any module.
     */
    @Nullable
    VirtualFile getContentRootForFile(@NotNull VirtualFile file);

    /**
     * Returns the module content root to which the specified file or directory belongs or null if the file does not belong to content of any module.
     *
     * @param honorExclusion if {@code false} the containing content root will be returned even if the file is located under a folder marked as excluded
     */
    @Nullable
    VirtualFile getContentRootForFile(@NotNull VirtualFile file, final boolean honorExclusion);


}
