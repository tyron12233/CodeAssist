package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.injected.editor.VirtualFileWindow;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIteratorEx;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.kotlin.com.intellij.util.containers.TreeNodeProcessingResult;

public abstract class FileIndexBase implements FileIndex {

    final DirectoryIndex myDirectoryIndex;

    FileIndexBase(@NotNull Project project) {
        myDirectoryIndex = DirectoryIndex.getInstance(project);;
    }

    protected abstract boolean isScopeDisposed();

    @Override
    public boolean iterateContent(@NotNull ContentIterator processor) {
        return iterateContent(processor, null);
    }

    @Override
    public boolean iterateContentUnderDirectory(@NotNull VirtualFile dir,
                                                @NotNull ContentIterator processor,
                                                @Nullable VirtualFileFilter customFilter) {
        ContentIteratorEx processorEx = toContentIteratorEx(processor);

        final VirtualFileVisitor.Result result = VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<Void>() {
            @NotNull
            @Override
            public Result visitFileEx(@NotNull VirtualFile file) {
                DirectoryInfo info = ReadAction.compute(() -> getInfoForFileOrDirectory(file));
                if (file.isDirectory()) {
                    if (info.isExcluded(file)) {
                        if (!info.processContentBeneathExcluded(file, content -> iterateContentUnderDirectory(content, processorEx, customFilter))) {
                            return skipTo(dir);
                        }
                        return SKIP_CHILDREN;
                    }
                    if (info.isIgnored() || info instanceof NonProjectDirectoryInfo && !((NonProjectDirectoryInfo)info).hasContentEntriesBeneath()) {
                        // it's certain nothing can be found under ignored directory
                        return SKIP_CHILDREN;
                    }
                }
                boolean accepted = ReadAction.compute(() -> !isScopeDisposed() && isInContent(file, info) &&
                                                            (customFilter == null || customFilter.accept(file)));
                TreeNodeProcessingResult status = accepted ? processorEx.processFileEx(file) : TreeNodeProcessingResult.CONTINUE;
                switch (status) {
                    case CONTINUE: return CONTINUE;
                    case SKIP_CHILDREN: return SKIP_CHILDREN;
                    case SKIP_TO_PARENT: return skipTo(file.getParent());
                    default:
                    case STOP: return skipTo(dir);
                }
            }
        });
        return !Comparing.equal(result.skipToParent, dir);
    }

    private static @NotNull ContentIteratorEx toContentIteratorEx(@NotNull ContentIterator processor) {
        if (processor instanceof ContentIteratorEx) {
            return (ContentIteratorEx)processor;
        }
        return fileOrDir -> processor.processFile(fileOrDir) ? TreeNodeProcessingResult.CONTINUE : TreeNodeProcessingResult.STOP;
    }

    @Override
    public boolean iterateContentUnderDirectory(@NotNull VirtualFile dir, @NotNull ContentIterator processor) {
        return iterateContentUnderDirectory(dir, processor, null);
    }

    boolean isTestSourcesRoot(@NotNull DirectoryInfo info) {
        Logger.getInstance("UNIMPLEMENTED").warn("isTestSourceRoot not yet implemented");
        return false;
    }

    /**
     * This method is for internal use only, and it'll be removed after switching to the new implementation of {@link ProjectFileIndex}.
     * Plugins must use methods from {@link ProjectFileIndex} instead.
     */
    @ApiStatus.Internal
    @NotNull
    public DirectoryInfo getInfoForFileOrDirectory(@NotNull VirtualFile file) {
        if (file instanceof VirtualFileWindow) {
            file = ((VirtualFileWindow)file).getDelegate();
        }
        return myDirectoryIndex.getInfoForFile(file);
    }

    protected boolean isInContent(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {
        return ProjectFileIndexImpl.isFileInContent(file, info);
    }
}
