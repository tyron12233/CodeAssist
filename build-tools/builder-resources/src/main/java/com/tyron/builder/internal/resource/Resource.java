package com.tyron.builder.internal.resource;

import com.tyron.builder.api.Describable;

/**
 * Represents some resource that may have content.
 *
 * <p>This type is currently pretty much empty. It's here as a place to extract and reuse stuff from the various subtypes, which all stared off as separate hierarchies.
 */
public interface Resource extends Describable {
    /**
     * Returns a display name for this resource. This can be used in log and error messages.
     *
     * @return the display name
     */
    @Override
    String getDisplayName();
}

