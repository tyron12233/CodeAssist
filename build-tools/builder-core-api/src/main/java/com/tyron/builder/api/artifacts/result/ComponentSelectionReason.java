package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.internal.HasInternalProtocol;
import com.tyron.builder.internal.scan.UsedByScanPlugin;

import java.util.List;

/**
 * Answers the question why a component was selected during the dependency resolution.
 *
 * @since 1.3
 */
@UsedByScanPlugin
@HasInternalProtocol
public interface ComponentSelectionReason {

    /**
     * Informs whether the component was forced. Users can force components via {@link com.tyron.builder.api.artifacts.ResolutionStrategy} or when declaring dependencies (see {@link
     * com.tyron.builder.api.artifacts.dsl.DependencyHandler}).
     */
    boolean isForced();

    /**
     * Informs whether the component was selected by conflict resolution. For more information about Gradle's conflict resolution please refer to the user manual. {@link
     * com.tyron.builder.api.artifacts.ResolutionStrategy} contains information about conflict resolution and includes means to configure it.
     */
    boolean isConflictResolution();

    /**
     * Informs whether the component was selected by the dependency substitution rule. Users can configure dependency substitution rules via {@link
     * com.tyron.builder.api.artifacts.ResolutionStrategy#getDependencySubstitution()}
     *
     * @since 1.4
     */
    boolean isSelectedByRule();

    /**
     * Informs whether the component is the requested selection of all dependency declarations, and was not replaced for some reason, such as conflict resolution.
     *
     * @since 1.11
     */
    boolean isExpected();

    /**
     * Informs whether the selected component is a project substitute from a build participating in in a composite build.
     *
     * @since 4.5
     */
    boolean isCompositeSubstitution();

    /**
     * Informs whether the selected component version has been influenced by a dependency constraint.
     *
     * @return true if a dependency constraint influenced the selection of this component
     *
     * @since 4.6
     */
    boolean isConstrained();

    /**
     * Returns a list of descriptions of the causes that led to the selection of this component.
     *
     * @return the list of descriptions.
     *
     * @since 4.6
     */
    List<ComponentSelectionDescriptor> getDescriptions();
}
