package com.tyron.builder.api.artifacts;

/**
 * <p>An {@code Artifact} represents an artifact included in a {@link com.tyron.builder.api.artifacts.Dependency}.</p>
 * An artifact is an (immutable) value object.
 */
public interface DependencyArtifact {
    String DEFAULT_TYPE = "jar";

    /**
     * Returns the name of this artifact.
     */
    String getName();

    /**
     * Sets the name of this artifact.
     */
    void setName(String name);

    /**
     * Returns the type of this artifact. Often the type is the same as the extension,
     * but sometimes this is not the case. For example for an ivy XML module descriptor, the type is
     * <em>ivy</em> and the extension is <em>xml</em>.
     *
     * @see #getExtension() 
     */
    String getType();

    /**
     * Sets the type of this artifact.
     */
    void setType(String type);

    /**
     * Returns the extension of this artifact. Often the extension is the same as the type,
     * but sometimes this is not the case. For example for an ivy XML module descriptor, the type is
     * <em>ivy</em> and the extension is <em>xml</em>.
     *
     * @see #getType() 
     */
    String getExtension();

    /**
     * Sets the extension of this artifact.
     */
    void setExtension(String extension);

    /**
     * Returns the classifier of this artifact.
     */
    String getClassifier();

    /**
     * Sets the classifier of this artifact.
     */
    void setClassifier(String classifier);

    /**
     * Returns an URL under which this artifact can be retrieved. If not
     * specified the user repositories are used for retrieving. 
     */
    String getUrl();

    /**
     * Sets the URL for this artifact.
     */
    void setUrl(String url);
}
