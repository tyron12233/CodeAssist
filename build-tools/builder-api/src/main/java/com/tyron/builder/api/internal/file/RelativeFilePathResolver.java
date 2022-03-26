package com.tyron.builder.api.internal.file;

/**
 * Resolves a path object relative to some base directory.
 */
public interface RelativeFilePathResolver {
    /**
     * Converts the given path to a relative path.
     */
    String resolveAsRelativePath(Object path);

    /**
     * Converts the given path to a path that is useful to display to a human, for example in logging or error reports.
     * Generally attempts to use a relative path, but may switch to an absolute path for files outside and some distance from the base directory.
     */
    String resolveForDisplay(Object path);
}