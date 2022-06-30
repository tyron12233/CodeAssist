package com.tyron.builder.api.internal.provider.sources;

import javax.annotation.Nullable;

public abstract class SystemPropertyValueSource extends AbstractPropertyValueSource<SystemPropertyValueSource.Parameters> {

    public interface Parameters extends AbstractPropertyValueSource.Parameters {}

    @Nullable
    @Override
    public String obtain() {
        @Nullable String propertyName = propertyNameOrNull();
        if (propertyName == null) {
            return null;
        }
        return System.getProperty(propertyName);
    }

    @Override
    public String getDisplayName() {
        return String.format("system property '%s'", propertyNameOrNull());
    }
}
