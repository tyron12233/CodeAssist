package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.internal.HasInternalProtocol;

import javax.annotation.Nullable;

/***
 * Represents a tuple of the component selector of a module and a candidate version
 * to be evaluated in a component selection rule.
 */
@HasInternalProtocol
public interface ComponentSelection {
    /**
     * Gets the candidate version of the module.
     *
     * @return the candidate version of the module
     */
    ModuleComponentIdentifier getCandidate();

    /**
     * Gets the metadata of the component.
     * <p>
     * The metadata may not be available, in which case {@code null} is returned.
     * Unavailable metadata may be caused by a module published without associated metadata.
     *
     * @return the {@code ComponentMetadata} or {@code null} if not available
     * @since 5.0
     */
    @Nullable
    ComponentMetadata getMetadata();

    /**
     * Used to access a specific descriptor format.
     * <p>
     * For an Ivy module, an {@link com.tyron.builder.api.artifacts.ivy.IvyModuleDescriptor ivy module descriptor} can be requested and returned.
     * <p>
     * If the descriptor type requested does not exist for the module under selection, {@code null} is returned.
     *
     * @param descriptorClass the descriptor class
     * @param <T> the descriptor type
     *
     * @return a descriptor fo the requested type, or {@code null} if there was none of the requested type.
     *
     * @see com.tyron.builder.api.artifacts.ivy.IvyModuleDescriptor
     * @since 5.0
     */
    @Nullable
    <T> T getDescriptor(Class<T> descriptorClass);

    /**
     * Rejects the candidate for the resolution.
     *
     * @param reason The reason the candidate was rejected.
     */
    void reject(String reason);
}
