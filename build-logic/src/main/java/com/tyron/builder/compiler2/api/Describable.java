package com.tyron.builder.compiler2.api;

/**
 * Types can implement this interface when they provide a human-readable display name.
 * It is strongly encouraged to compute this display name lazily: computing a display name,
 * even if it's only a string concatenation, can take a significant amount of time during
 * configuration for something that would only be used, typically, in error messages.
 *
 */
public interface Describable {

    /**
     * Returns the display name of this object. It is strongly encouraged to compute it
     * lazily, and cache the value if it is expensive.
     * @return the display name
     */
    String getDisplayName();
}
