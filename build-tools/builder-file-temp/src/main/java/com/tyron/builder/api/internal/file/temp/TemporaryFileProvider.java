package com.tyron.builder.api.internal.file.temp;

import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import org.jetbrains.annotations.Nullable;

import java.io.File;

@ServiceScope(Scope.Global.class)
public interface TemporaryFileProvider {
    /**
     * Allocates a new temporary file with the exact specified path,
     * relative to the temporary file directory. Does not create the file.
     * Provides no guarantees around whether the file already exists.
     *
     * @param path The tail path components for the file.
     * @return The file
     */
    File newTemporaryFile(String... path);

    /**
     * Allocates and creates a new temporary file with the given prefix, suffix,
     * and path, relative to the temporary file directory.
     */
    File createTemporaryFile(String prefix, @Nullable String suffix, String... path);

    File createTemporaryDirectory(String prefix, @Nullable String suffix, String... path);
}