package com.tyron.builder.api.internal.provider.sources;

import com.tyron.builder.api.internal.properties.GradleProperties;

import javax.annotation.Nullable;
import javax.inject.Inject;

public abstract class GradlePropertyValueSource extends AbstractPropertyValueSource<GradlePropertyValueSource.Parameters> {

    public interface Parameters extends AbstractPropertyValueSource.Parameters {
    }

    @Inject
    protected abstract GradleProperties getGradleProperties();

    @Nullable
    @Override
    public String obtain() {
        @Nullable String propertyName = propertyNameOrNull();
        if (propertyName == null) {
            return null;
        }
        return (String) getGradleProperties().find(propertyName);
    }

    @Override
    public String getDisplayName() {
        return String.format("Gradle property '%s'", propertyNameOrNull());
    }
}
