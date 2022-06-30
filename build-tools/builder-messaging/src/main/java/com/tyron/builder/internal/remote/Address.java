package com.tyron.builder.internal.remote;

import java.io.Serializable;

/**
 * The address for a communication endpoint. Addresses are immutable.
 */
public interface Address extends Serializable {
    /**
     * Returns the display name for this address. Implementations should also override toString() to return the display name.
     *
     * @return The display name.
     */
    String getDisplayName();
}
