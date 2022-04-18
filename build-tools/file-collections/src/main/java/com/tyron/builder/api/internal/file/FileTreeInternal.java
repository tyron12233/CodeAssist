package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.tasks.util.PatternFilterable;

import java.util.function.Consumer;

public interface FileTreeInternal extends FileTree, FileCollectionInternal {
    String getDisplayName();

    void visitContentsAsFileTrees(Consumer<FileTreeInternal> visitor);

    @Override
    FileTreeInternal matching(PatternFilterable patterns);
}