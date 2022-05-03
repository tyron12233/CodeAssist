package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.file.FileCollection;

import java.util.Map;

public interface DependencyResolveContext {
    boolean isTransitive();

    /**
     * Accepts either a {@link ResolvableDependency} or {@link FileCollection}
     */
    void add(Object dependency);

    Map<String, String> getAttributes();
}
