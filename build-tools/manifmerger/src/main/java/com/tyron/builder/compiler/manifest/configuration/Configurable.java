package com.tyron.builder.compiler.manifest.configuration;


import androidx.annotation.NonNull;

/**
 * An object that is associated with a {@link FolderConfiguration}.
 */
public interface Configurable {
    /** Returns the {@link FolderConfiguration} for this object. */
    @NonNull
    FolderConfiguration getConfiguration();
}