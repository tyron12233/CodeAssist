package org.gradle.api.internal.file;

import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.util.PatternFilterable;

import java.util.function.Consumer;

public interface FileTreeInternal extends FileTree, FileCollectionInternal {
    String getDisplayName();

    void visitContentsAsFileTrees(Consumer<FileTreeInternal> visitor);

    @Override
    FileTreeInternal matching(PatternFilterable patterns);
}