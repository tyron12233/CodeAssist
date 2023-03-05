package org.gradle.api.artifacts;

import org.gradle.api.Action;
import org.gradle.api.attributes.AttributeContainer;

import java.util.List;

/**
 * A component metadata builder.
 *
 * @since 4.0
 */
public interface ComponentMetadataBuilder {
    /**
     * Sets the status of this component
     * @param status the component status
     */
    void setStatus(String status);

    /**
     * Sets the status scheme of this component
     * @param scheme the status scheme
     */
    void setStatusScheme(List<String> scheme);

    /**
     * Configures the attributes of this component
     * @param attributesConfiguration the configuration action
     *
     * @since 4.9
     */
    void attributes(Action<? super AttributeContainer> attributesConfiguration);

    /**
     * Returns the attributes of the component.
     * @return the attributes of the component, guaranteed to be mutable.
     *
     * @since 4.9
     */
    AttributeContainer getAttributes();
}
