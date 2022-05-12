package com.tyron.builder.api.artifacts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides access to component metadata from a {@link ComponentMetadataRule}.
 *
 * @since 4.9
 */
public interface ComponentMetadataContext {

    /**
     * Used to access a specific descriptor format.
     *
     * @param descriptorClass the descriptor class
     * @param <T> the descriptor type
     *
     * @return a descriptor, or {@code null} if there was none of the requested type.
     *
     * @see com.tyron.builder.api.artifacts.ivy.IvyModuleDescriptor
     * @see com.tyron.builder.api.artifacts.maven.PomModuleDescriptor
     */
    @Nullable
    <T> T getDescriptor(Class<T> descriptorClass);

    @Nonnull
    ComponentMetadataDetails getDetails();
}
