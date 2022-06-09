package com.tyron.builder.internal.exceptions;

public class ConfigurationNotConsumableException extends IllegalArgumentException {
    public ConfigurationNotConsumableException(String targetComponent, String configurationName) {
        super("Selected configuration '" + configurationName + "' on '" + targetComponent + "' but it can't be used as a project dependency because it isn't intended for consumption by other components.");
    }
}
