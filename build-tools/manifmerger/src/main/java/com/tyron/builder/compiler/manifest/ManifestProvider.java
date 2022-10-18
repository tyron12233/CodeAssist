package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * An Object that provides a manifest.
 */
public interface ManifestProvider {

    /**
     * Returns the location of the manifest.
     */
    @NotNull
    File getManifest();

    /**
     * Returns a user friendly name.
     */
    @Nullable
    String getName();
}
