package com.tyron.builder.internal.fingerprint;

import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;

/**
 * A snapshotter for generic file trees, which are not based on a directory on disk.
 *
 * Examples of a generic file tree is a {@link TarFileTree} backed by a non-file resource.
 * This is needed to build a Merkle directory tree from the elements of a file tree obtained by {@link FileTree#visit(FileVisitor)}.
 */
public interface GenericFileTreeSnapshotter {
    FileSystemSnapshot snapshotFileTree(FileTreeInternal tree);
}