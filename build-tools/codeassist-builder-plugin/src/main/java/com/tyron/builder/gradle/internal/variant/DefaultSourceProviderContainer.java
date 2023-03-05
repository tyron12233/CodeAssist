package com.tyron.builder.gradle.internal.variant;

import com.android.annotations.NonNull;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.SourceProviderContainer;

/**
 * Default implementation of a SourceProviderContainer that wraps an existing instance of a
 * SourceProvider.
 */
public class DefaultSourceProviderContainer implements SourceProviderContainer {

    @NonNull
    private final String name;
    @NonNull
    private final SourceProvider sourceProvider;

    public DefaultSourceProviderContainer(@NonNull String name,
                                          @NonNull SourceProvider sourceProvider) {
        this.name = name;
        this.sourceProvider = sourceProvider;
    }

    @NonNull
    @Override
    public String getArtifactName() {
        return name;
    }

    @NonNull
    @Override
    public SourceProvider getSourceProvider() {
        return sourceProvider;
    }
}