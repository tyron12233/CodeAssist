package com.tyron.builder.api.artifacts;

import javax.annotation.Nullable;

/**
 * A {@link PublishArtifact} whose properties can be modified.
 */
public interface ConfigurablePublishArtifact extends PublishArtifact {
    /**
     * Sets the name of this artifact.
     *
     * @param name The name. Should not be null.
     */
    void setName(String name);

    /**
     * Sets the extension of this artifact.
     *
     * @param extension The extension. Should not be null.
     */
    void setExtension(String extension);

    /**
     * Sets the type of this artifact.
     *
     * @param type The type. Should not be null.
     */
    void setType(String type);

    /**
     * Sets the classifier of this artifact.
     *
     * @param classifier The classifier. May be null.
     */
    void setClassifier(@Nullable String classifier);

    /**
     * Registers some tasks which build this artifact.
     *
     * @param tasks The tasks. These are evaluated as per {@link org.gradle.api.Task#dependsOn(Object...)}.
     * @return this
     */
    ConfigurablePublishArtifact builtBy(Object... tasks);
}
