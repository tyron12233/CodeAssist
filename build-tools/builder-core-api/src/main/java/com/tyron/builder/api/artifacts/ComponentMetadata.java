package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.attributes.HasAttributes;

import java.util.List;

/**
 * Provides a read-only view of a resolved component's metadata, which typically originates from
 * a component descriptor (Ivy file, Maven POM).
 */
public interface ComponentMetadata extends HasAttributes {
    /**
     * Returns the identifier of the component.
     *
     * @return the identifier of the component.
     */
    ModuleVersionIdentifier getId();

    /**
     * Tells whether the component is changing or immutable.
     *
     * @return whether the component is changing or immutable.
     */
    boolean isChanging();

    /**
     * Returns the status of the component. Must
     * match one of the values in {@link #getStatusScheme()}.
     *
     * <p>
     * For an external module component, the status is determined from the module descriptor:
     * <ul>
     *     <li>For modules in an Ivy repository, this value is taken from the published ivy descriptor.</li>
     *     <li>For modules in a Maven repository, this value will be "integration" for a SNAPSHOT module, and "release" for all non-SNAPSHOT modules.</li>
     * </ul>
     *
     * @return the status of the component
     */
    String getStatus();

    /**
     * Returns the status scheme of the component. Values are
     * ordered from least to most mature status.
     * Defaults to {@code ["integration", "milestone", "release"]}.
     *
     * @return the status scheme of the component
     */
    List<String> getStatusScheme();

}
