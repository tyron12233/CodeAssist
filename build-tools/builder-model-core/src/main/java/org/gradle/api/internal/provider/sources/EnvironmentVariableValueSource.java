package org.gradle.api.internal.provider.sources;

import org.gradle.api.Describable;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

import javax.annotation.Nullable;

public abstract class EnvironmentVariableValueSource implements ValueSource<String, EnvironmentVariableValueSource.Parameters>, Describable {

    public interface Parameters extends ValueSourceParameters {
        Property<String> getVariableName();
    }

    @Nullable
    @Override
    public String obtain() {
        @Nullable String variableName = variableNameOrNull();
        if (variableName == null) {
            return null;
        }
        return System.getenv(variableName);
    }

    @Override
    public String getDisplayName() {
        return String.format("environment variable '%s'", variableNameOrNull());
    }

    private String variableNameOrNull() {
        return getParameters().getVariableName().getOrNull();
    }
}
