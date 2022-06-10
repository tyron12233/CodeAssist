package org.gradle.internal.fingerprint;

import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.internal.snapshot.FileSystemSnapshot;

/**
 * A snapshotter for generic file trees, which are not based on a directory on disk.
 *
 * Examples of a generic file tree is a {@link TarFileTree} backed by a non-file resource.
 * This is needed to build a Merkle directory tree from the elements of a file tree obtained by {@link FileTree#visit(FileVisitor)}.
 */
public interface GenericFileTreeSnapshotter {
    FileSystemSnapshot snapshotFileTree(FileTreeInternal tree);
}