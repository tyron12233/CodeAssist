package org.gradle.api.internal.provider.sources;

import org.gradle.api.Describable;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

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
