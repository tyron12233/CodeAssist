package com.tyron.builder.api.artifacts.transform;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

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
