package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;

/**
 * Represents an element in a model. Elements are arranged in a hierarchy.
 */
@Incubating
public interface ModelElement extends Named {
    /**
     * Returns the name of this element. Each element has a name associated with it, that uniquely identifies the element amongst its siblings.
     * Some element have their name generated or automatically assigned, and for these elements the name may not be human consumable.
     */
    @Override
    String getName();

    /**
     * Returns a human-consumable display name for this element.
     */
    String getDisplayName();
}
