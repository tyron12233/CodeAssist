package com.tyron.builder.compiler.manifest.configuration;


import org.jetbrains.annotations.NotNull;

/**
 * An object that is associated with a {@link FolderConfiguration}.
 */
public interface Configurable {
    /** Returns the {@link FolderConfiguration} for this object. */
    @NotNull
    FolderConfiguration getConfiguration();
}