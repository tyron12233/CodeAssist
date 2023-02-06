package com.tyron.completion.psi.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public abstract class FileBasedIndex {

    public abstract void iterateIndexableFiles(@NotNull ContentIterator processor, @NotNull Project project, @Nullable ProgressIndicator indicator);

    /**
     * @return the file which the current thread is indexing right now, or {@code null} if current thread isn't indexing.
     */
    @Nullable
    public abstract VirtualFile getFileBeingCurrentlyIndexed();
}
