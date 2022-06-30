package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.util.internal.ConfigureUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class ConfigureByMapAction<T> implements Action<T> {

    private final Map<?, ?> properties;
    private final Collection<?> mandatoryProperties;

    public ConfigureByMapAction(Map<?, ?> properties) {
        this(properties, Collections.emptySet());
    }

    public ConfigureByMapAction(Map<?, ?> properties, Collection<?> mandatoryProperties) {
        this.properties = properties;
        this.mandatoryProperties = mandatoryProperties;
    }

    @Override
    public void execute(T thing) {
        ConfigureUtil.configureByMap(properties, thing, mandatoryProperties);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ConfigureByMapAction that = (ConfigureByMapAction) o;

        if (!mandatoryProperties.equals(that.mandatoryProperties)) {
            return false;
        }
        if (!properties.equals(that.properties)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = properties.hashCode();
        result = 31 * result + mandatoryProperties.hashCode();
        return result;
    }
}
