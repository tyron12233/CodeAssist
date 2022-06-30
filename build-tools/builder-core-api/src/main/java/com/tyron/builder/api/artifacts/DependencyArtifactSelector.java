package com.tyron.builder.api.artifacts;

import javax.annotation.Nullable;

/**
 * Details about an artifact selection in the context of a dependency substitution.
 *
 * Artifact selections are handy as a migration path from the Maven or Ivy ecosystem,
 * where different "variants" are actually represented as different artifacts, with
 * specific (type, extension, classifier) sub-coordinates, in addition to the GAV
 * (group, artifact, version) coordinates.
 *
 * It is preferable to use component metadata rules to properly describe the variants
 * of a module, so this variant selector should only be used when defining such rules
 * is not possible or too complex for the use case.
 *
 * @since 6.6
 */
public interface DependencyArtifactSelector {
    /**
     * Returns the type of the artifact to select
     */
    String getType();

    /**
     * Returns the extension of the artifact to select. If it returns null, it will fallback to jar.
     */
    @Nullable
    String getExtension();

    /**
     * Returns the classifier of the artifact to select.
     */
    @Nullable
    String getClassifier();
}
