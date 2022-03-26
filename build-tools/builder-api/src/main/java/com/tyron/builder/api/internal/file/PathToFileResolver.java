package com.tyron.builder.api.internal.file;

import java.io.File;

/**
 * Resolves some path object to a `File`. May or may not be able to resolve relative paths.
 */
public interface PathToFileResolver {
    /**
     * Resolves the given path to a file.
     */
    File resolve(Object path);

    /**
     * Returns a resolver that resolves paths relative to the given base dir.
     */
    PathToFileResolver newResolver(File baseDir);

    /**
     * Indicates if this resolver is able to resolved relative paths.
     *
     * @return {@code true} if it can resolve relative path, {@code false} otherwise.
     */
    boolean canResolveRelativePath();
}