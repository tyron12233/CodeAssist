package com.tyron.builder.api.internal.file.collections;

/**
 * A file tree which maintains a local copy of itself on the filesystem.
 */
public interface FileSystemMirroringFileTree extends MinimalFileTree {
    /**
     * Returns the directory tree that will contain the copy of this file tree, after all elements of this tree have been visited. It is the caller's responsibility to visit the
     * elements of this tree before using the returned directory tree.
     */
    DirectoryFileTree getMirror();
}