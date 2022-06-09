package com.tyron.builder.model;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.Incubating;

/**
 * Thrown when a cycle is encountered while configuring a model element.
 */
@Incubating
public class ConfigurationCycleException extends BuildException {
    public ConfigurationCycleException(String message) {
        super(message);
    }
}
