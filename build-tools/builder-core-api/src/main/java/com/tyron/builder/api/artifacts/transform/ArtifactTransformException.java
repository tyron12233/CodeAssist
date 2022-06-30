package com.tyron.builder.api.artifacts.transform;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.internal.exceptions.Contextual;

import java.io.File;

/**
 * An exception to report a problem during transformation execution.
 *
 * @since 3.4
 */
@Deprecated
@Contextual
public class ArtifactTransformException extends BuildException {

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
