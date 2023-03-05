package org.gradle.api.artifacts.transform;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.internal.exceptions.Contextual;

import java.io.File;

/**
 * An exception to report a problem during transformation execution.
 *
 * @since 3.4
 */
@Deprecated
@Contextual
public class ArtifactTransformException extends GradleException {

    public ArtifactTransformException(File input, AttributeContainer expectedAttributes, @SuppressWarnings("unused") Class<? extends ArtifactTransform> transform, Throwable cause) {
        this(input, expectedAttributes, cause);
    }

    /**
     * Capture a failure during the execution of a file-based transformation.
     *
     * @since 5.1
     */
    public ArtifactTransformException(File file, AttributeContainer expectedAttributes, Throwable cause) {
        super(String.format("Failed to transform file '%s' to match attributes %s",
            file.getName(), expectedAttributes), cause);
    }

    /**
     * Capture a failure during the execution of a file-based transformation.
     *
     * @since 5.1
     */
    public ArtifactTransformException(ComponentArtifactIdentifier artifact, AttributeContainer expectedAttributes, Throwable cause) {
        super(String.format("Failed to transform artifact '%s' to match attributes %s",
            artifact, expectedAttributes), cause);
    }
}
