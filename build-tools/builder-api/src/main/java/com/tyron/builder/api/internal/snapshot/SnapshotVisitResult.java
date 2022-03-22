package com.tyron.builder.api.internal.snapshot;

/**
 * Ways to continue visiting a snapshot hierarchy after an entry has been visited.
 *
 * @see java.nio.file.FileVisitResult
 */
public enum SnapshotVisitResult {

    /**
     * Continue visiting. When returned after visiting a directory,
     * the entries in the directory will be visited next.
     */
    CONTINUE,

    /**
     * Terminate visiting immediately.
     */
    TERMINATE,

    /**
     * If returned from visiting a directory, the directories entries will not be visited;
     * otherwise works as {@link #CONTINUE}.
     */
    SKIP_SUBTREE
}