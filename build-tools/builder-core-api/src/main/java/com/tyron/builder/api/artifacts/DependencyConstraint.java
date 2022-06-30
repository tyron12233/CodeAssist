package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.attributes.HasConfigurableAttributes;

import javax.annotation.Nullable;

/**
 * Represents a constraints over all, including transitive, dependencies.
 *
 * @since 4.5
 */
public interface DependencyConstraint extends ModuleVersionSelector, HasConfigurableAttributes<DependencyConstraint> {

    /**
     * Configures the version constraint for this dependency constraint.
     *
     * @param configureAction the configuration action for the module version
     */
    void version(Action<? super MutableVersionConstraint> configureAction);

    /**
     * Returns a reason why this dependency constraint should be used, in particular with regards to its version. The dependency report will use it to explain why a specific dependency was selected, or why a
     * specific dependency version was used.
     *
     * @return a reason to use this dependency constraint
     * @since 4.6
     */
    @Nullable
    String getReason();

    /**
     * Sets the reason why this dependency constraint should be used.
     *
     * @since 4.6
     */
    void because(@Nullable String reason);

    /**
     * Returns the attributes for this constraint. Mutation of the attributes of a constraint must be done through
     * the {@link #attributes(Action)} method.
     *
     * @return the attributes container for this dependency
     *
     * @since 4.8
     */
    @Override
    AttributeContainer getAttributes();

    /**
     * Mutates the attributes of this constraint. Attributes are used during dependency resolution to select the appropriate
     * target variant, in particular when a single component provides different variants.
     *
     * @param configureAction the attributes mutation action
     *
     * @since 4.8
     */
    @Override
    DependencyConstraint attributes(Action<? super AttributeContainer> configureAction);

    /**
     * Returns the version constraint to be used during selection.
     * @return the version constraint
     */
    VersionConstraint getVersionConstraint();
}
