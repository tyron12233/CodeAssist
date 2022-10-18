package com.tyron.builder.gradle.api;

import org.gradle.api.artifacts.component.ComponentIdentifier;

/**
 * Component Identifier for a tested artifact.
 *
 * <p>This can be used with {@link BaseVariant#getCompileClasspathArtifacts(Object)} to disambiguate
 * the dependencies vs the tested artifact(s).
 */
@Deprecated
public interface TestedComponentIdentifier extends ComponentIdentifier {

    /**
     * returns the name of the tested variant.
     *
     * @return the name of the tested variant.
     */
    String getVariantName();
}
