package org.gradle.api.artifacts.transform;

import org.gradle.api.BuildException;
import org.gradle.internal.exceptions.Contextual;

/**
 * An exception to report a problem during transformation execution.
 *
 * @since 3.5
 */
@Contextual
public class VariantTransformConfigurationException extends BuildException {
    public VariantTransformConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public VariantTransformConfigurationException(String message) {
        super(message);
    }
}
