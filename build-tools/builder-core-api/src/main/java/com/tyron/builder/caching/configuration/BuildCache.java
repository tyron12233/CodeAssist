package com.tyron.builder.caching.configuration;

/**
 * Configuration object for a build cache.
 *
 * @since 3.5
 */
public interface BuildCache {

    /**
     * Returns whether the build cache is enabled.
     */
    boolean isEnabled();

    /**
     * Sets whether the build cache is enabled.
     */
    void setEnabled(boolean enabled);

    /**
     * Returns whether a given build can store outputs in the build cache.
     */
    boolean isPush();

    /**
     * Sets whether a given build can store outputs in the build cache.
     */
    void setPush(boolean enabled);
}