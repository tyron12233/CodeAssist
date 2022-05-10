package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.attributes.AttributeContainer;

import javax.annotation.Nullable;

/**
 * Describes a metadata about a dependency - direct dependency or dependency constraint - declared in a resolved component's metadata.
 *
 * @param <SELF> type extending this interface
 * @since 4.4
 */
public interface DependencyMetadata<SELF extends DependencyMetadata> {

    /**
     * Returns the group of the module that is targeted by this dependency or dependency constraint.
     * The group allows the definition of modules of the same name in different organizations or contexts.
     */
    String getGroup();

    /**
     * Returns the name of the module that is targeted by this dependency or dependency constraint.
     */
    String getName();

    /**
     * Returns the version of the module that is targeted by this dependency or dependency constraint.
     * which usually expresses what API level of the module you are compatible with.
     *
     * @since 4.5
     */
    VersionConstraint getVersionConstraint();

    /**
     * Adjust the version constraints of the dependency or dependency constraint.
     *
     * @param configureAction modify version details
     * @since 4.5
     */
    SELF version(Action<? super MutableVersionConstraint> configureAction);

    /**
     * Returns the reason why this dependency should be selected.
     *
     * @return the reason, or null if no reason is found in metadata.
     *
     * @since 4.6
     */
    @Nullable
    String getReason();

    /**
     * Adjust the reason why this dependency should be selected.
     *
     * @param reason modified reason
     *
     * @since 4.6
     */
    SELF because(String reason);

    /**
     * Returns the attributes of this dependency.
     *
     * @return the attributes of this dependency
     *
     * @since 4.8
     */
    AttributeContainer getAttributes();

    /**
     * Adjust the attributes of this dependency
     *
     * @since 4.8
     */
    SELF attributes(Action<? super AttributeContainer> configureAction);

    /**
     * The module identifier of the component. Returns the same information
     * as {@link #getGroup()} and {@link #getName()}.
     *
     * @return the module identifier
     *
     * @since 4.9
     */
    ModuleIdentifier getModule();

}
