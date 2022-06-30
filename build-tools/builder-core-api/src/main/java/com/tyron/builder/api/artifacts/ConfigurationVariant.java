package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.attributes.HasConfigurableAttributes;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * Represents some variant of an outgoing configuration.
 *
 * @since 3.3
 */
@HasInternalProtocol
public interface ConfigurationVariant extends Named, HasConfigurableAttributes<ConfigurationVariant> {

    /**
     * Returns the artifacts associated with this variant.
     */
    PublishArtifactSet getArtifacts();

    /**
     * Adds an artifact to this variant.
     *
     * <p>See {@link com.tyron.builder.api.artifacts.dsl.ArtifactHandler} for details of the supported notations.
     */
    void artifact(Object notation);

    /**
     * Adds an artifact to this variant, configuring it using the given action.
     *
     * <p>See {@link com.tyron.builder.api.artifacts.dsl.ArtifactHandler} for details of the supported notations.
     */
    void artifact(Object notation, Action<? super ConfigurablePublishArtifact> configureAction);
}
