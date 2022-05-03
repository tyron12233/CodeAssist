package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Buildable;
import com.tyron.builder.internal.HasInternalProtocol;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Date;

/**
 * <p>A {@code PublishArtifact} is an artifact produced by a project.</p>
 */
@HasInternalProtocol
public interface PublishArtifact extends Buildable {
    /**
     * Returns the name of the artifact.
     *
     * @return The name. Never null.
     */
    String getName();

    /**
     * Returns the extension of this published artifact. Often the extension is the same as the type,
     * but sometimes this is not the case. For example for an ivy XML module descriptor, the type is
     * <em>ivy</em> and the extension is <em>xml</em>.
     *
     * @return The extension. Never null.
     */
    String getExtension();

    /**
     * Returns the type of the published artifact. Often the type is the same as the extension,
     * but sometimes this is not the case. For example for an ivy XML module descriptor, the type is
     * <em>ivy</em> and the extension is <em>xml</em>.
     *
     * @return The type. Never null.
     */
    String getType();

    /**
     * Returns the classifier of this published artifact, if any.
     *
     * @return The classifier. May be null.
     */
    @Nullable
    String getClassifier();

    /**
     * Returns the file of this artifact.
     *
     * @return The file. Never null.
     */
    File getFile();

    /**
     * Returns the date that should be used when publishing this artifact. This is used
     * in the module descriptor accompanying this artifact (the ivy.xml). If the date is
     * not specified, the current date is used. If this artifact
     * is published without an module descriptor, this property has no relevance.
     *
     * @return The date. May be null.
     */
    @Nullable
    Date getDate();

}
