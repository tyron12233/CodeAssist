package com.tyron.builder.api.artifacts.maven;

/**
 * The metadata about a Maven POM that acts as an input to a component metadata rule.
 *
 * @since 6.3
 */
public interface PomModuleDescriptor {

    /**
     * Returns the packaging for this POM.
     *
     * @return the packaging type
     */
    String getPackaging();

}
