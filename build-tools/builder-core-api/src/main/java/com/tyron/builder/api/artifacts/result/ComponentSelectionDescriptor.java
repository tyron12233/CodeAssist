package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.internal.HasInternalProtocol;
import com.tyron.builder.internal.scan.UsedByScanPlugin;

/**
 * A component selection description, which wraps a cause with an optional custom description.
 *
 * @since 4.6
 */
@UsedByScanPlugin
@HasInternalProtocol
public interface ComponentSelectionDescriptor {

    /**
     * Returns the cause associated with this descriptor
     *
     * @return a component selection cause
     */
    ComponentSelectionCause getCause();

    /**
     * Returns a description for the selection. This may be the default description of a {@link ComponentSelectionCause cause},
     * or a custom description provided typically through a rule.
     *
     * @return the description of this component selection
     */
    String getDescription();

}
