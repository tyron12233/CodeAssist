package com.tyron.builder.model;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Options for build-time support for vector drawables.
 */
public interface VectorDrawablesOptions {

    @Nullable
    Set<String> getGeneratedDensities();

    @Nullable
    Boolean getUseSupportLibrary();
}