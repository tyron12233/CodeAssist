package org.gradle.model;

import org.gradle.api.GradleException;
import org.gradle.api.Incubating;

/**
 * Thrown when a cycle is encountered while configuring a model element.
 */
@Incubating
public class ConfigurationCycleException extends GradleException {
    public ConfigurationCycleException(String message) {
        super(message);
    }
}
