package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.internal.scan.UsedByScanPlugin;

/**
 * The possible component selection causes. There are a limited number of causes, but each of them
 * can be provided with a custom description, via {@link ComponentSelectionDescriptor}.
 *
 * @since 4.6
 */
@UsedByScanPlugin
public enum ComponentSelectionCause {
    /**
     * This component was selected because it's the root component.
     */
    ROOT("root"),

    /**
     * This component was selected because it was requested directly.
     */
    REQUESTED("requested"),

    /**
     * This component was selected by a rule.
     */
    SELECTED_BY_RULE("selected by rule"),

    /**
     * This component was selected because selection was forced on this version.
     */
    FORCED("forced"),

    /**
     * This component was selected between several candidates during conflict resolution.
     */
    CONFLICT_RESOLUTION("conflict resolution"),

    /**
     * This component was selected as a participant of a composite.
     */
    COMPOSITE_BUILD("composite build substitution"),

    /**
     * This component was selected because another version was rejected by a rule
     */
    REJECTION("rejection"),

    /**
     * This component was selected because of a dependency constraint
     */
    CONSTRAINT("constraint"),

    /**
     * This component was selected because it was requested by a parent with a strict version.
     *
     * @since 6.0
     */
    BY_ANCESTOR("by ancestor");

    private final String defaultReason;

    ComponentSelectionCause(String defaultReason) {

        this.defaultReason = defaultReason;
    }

    public String getDefaultReason() {
        return defaultReason;
    }
}
