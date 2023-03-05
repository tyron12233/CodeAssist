package com.tyron.builder.api.transform;

/**
 * The file changed status for incremental execution.
 * @deprecated
 */
@Deprecated
public enum Status {
    /**
     * The file was not changed since the last build.
     */
    NOTCHANGED,
    /**
     * The file was added since the last build.
     */
    ADDED,
    /**
     * The file was modified since the last build.
     */
    CHANGED,
    /**
     * The file was removed since the last build.
     */
    REMOVED;
}
