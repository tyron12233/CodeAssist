package com.tyron.builder.api.internal.provider.sources;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.ValueSource;
import com.tyron.builder.api.provider.ValueSourceParameters;

import javax.annotation.Nullable;

public abstract class AbstractPropertyValueSource<P extends AbstractPropertyValueSource.Parameters> implements ValueSource<String, P>, Describable {

    public interface Parameters extends ValueSourceParameters {
        Property<String> getPropertyName();
    }

    @Nullable
    @Override
    public abstract String obtain();

    @Override
    public abstract String getDisplayName();

    @Nullable
    protected String propertyNameOrNull() {
        return getParameters().getPropertyName().getOrNull();
    }
}
