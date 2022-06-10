package org.gradle.model;

import org.gradle.api.BuildException;
import org.gradle.api.Incubating;

/**
 * Thrown when a cycle is encountered while configuring a model element.
 */
@Incubating
public class ConfigurationCycleException extends BuildException {
    public ConfigurationCycleException(String message) {
        super(message);
    }
}
